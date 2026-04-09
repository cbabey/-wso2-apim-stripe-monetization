/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.apim.monetization.impl.workflow;

import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Plan;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.model.Token;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.net.RequestOptions;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.apim.monetization.impl.StripeMonetizationDAO;
import org.wso2.apim.monetization.impl.StripeMonetizationException;
import org.wso2.apim.monetization.impl.model.MonetizationPlatformCustomer;
import org.wso2.apim.monetization.impl.model.MonetizationSharedCustomer;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.Subscriber;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.SubscriptionWorkflowDTO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.workflow.GeneralWorkflowResponse;
import org.wso2.carbon.apimgt.impl.workflow.HttpWorkflowResponse;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowConstants;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;
import org.wso2.carbon.apimgt.persistence.APIPersistence;
import org.wso2.carbon.apimgt.persistence.PersistenceManager;
import org.wso2.carbon.apimgt.persistence.dto.Organization;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPI;
import org.wso2.carbon.apimgt.persistence.exceptions.APIPersistenceException;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * worrkflow executor for stripe based subscription create action
 */
public class StripeSubscriptionCreationWorkflowExecutor extends WorkflowExecutor {

    private static final Log log = LogFactory.getLog(StripeSubscriptionCreationWorkflowExecutor.class);
    StripeMonetizationDAO stripeMonetizationDAO = StripeMonetizationDAO.getInstance();
    APIPersistence apiPersistenceInstance;

    /**
     * DevPortal URL to redirect the user after successful card entry on Stripe Checkout.
     * Configure in workflow-extensions.xml:
     * <Property name="checkoutSuccessUrl">https://devportal.example.com/subscription/success</Property>
     */
    private String checkoutSuccessUrl;

    /**
     * DevPortal URL to redirect the user if they cancel on Stripe Checkout.
     * Configure in workflow-extensions.xml:
     * <Property name="checkoutCancelUrl">https://devportal.example.com/subscription/cancel</Property>
     */
    private String checkoutCancelUrl;

    public void setCheckoutSuccessUrl(String checkoutSuccessUrl) {
        this.checkoutSuccessUrl = checkoutSuccessUrl;
    }

    public void setCheckoutCancelUrl(String checkoutCancelUrl) {
        this.checkoutCancelUrl = checkoutCancelUrl;
    }

    @Override
    public String getWorkflowType() {
        return WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION;
    }

    @Override
    public List<WorkflowDTO> getWorkflowDetails(String workflowStatus) throws WorkflowException {
        return null;
    }

    /**
     * This method executes subscription creation workflow and return workflow response back to the caller
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @return workflow response back to the caller
     * @throws WorkflowException Thrown when the workflow execution was not fully performed
     */
    @Override
    public WorkflowResponse execute(WorkflowDTO workflowDTO) throws WorkflowException {

        SubscriptionWorkflowDTO subsWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        workflowDTO.setProperties("apiName", subsWorkflowDTO.getApiName());
        workflowDTO.setProperties("apiVersion", subsWorkflowDTO.getApiVersion());
        workflowDTO.setProperties("subscriber", subsWorkflowDTO.getSubscriber());
        workflowDTO.setProperties("applicationName", subsWorkflowDTO.getApplicationName());
        super.execute(workflowDTO);
        workflowDTO.setStatus(WorkflowStatus.APPROVED);
        WorkflowResponse workflowResponse = complete(workflowDTO);

        return workflowResponse;
    }

    /**
     * This method executes monetization related functions in the subscription creation workflow
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @param api         API
     * @return workflow response back to the caller
     * @throws WorkflowException Thrown when the workflow execution was not fully performed
     */
    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, API api) throws WorkflowException {

        boolean isMonetizationEnabled = false;
        SubscriptionWorkflowDTO subWorkFlowDTO = null;
        String stripePlatformAccountKey = null;
        Subscriber subscriber = null;
        Customer customer = null;
        Customer sharedCustomerBE = null;
        MonetizationPlatformCustomer monetizationPlatformCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer;
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;

        Properties properties = new Properties();
        properties.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
        properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS, APIUtil.isAllowDisplayMultipleVersions());
        Map<String, String> configMap = new HashMap<>();
        Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
        if (configs != null && !configs.isEmpty()) {
            configMap.putAll(configs);
        }
        configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
        apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap, properties);

        //read the platform account key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());
        String connectedAccountKey = StringUtils.EMPTY;
        Organization org = new Organization(workflowDTO.getTenantDomain());
        PublisherAPI publisherAPI = null;
        try {
            publisherAPI = apiPersistenceInstance.getPublisherAPI(org, api.getUUID());
        } catch (APIPersistenceException e) {
            throw new WorkflowException("Failed to retrieve the API of UUID: " +api.getUUID(), e);
        }
        Map<String, String> monetizationProperties = new Gson().fromJson(publisherAPI.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for API : "
                        + api.getId().getApiName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to create artifacts in the stripe connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try (Connection con = APIMgtDBUtil.getConnection()) {
            subscriber = apiMgtDAO.getSubscriber(subWorkFlowDTO.getSubscriber());
            // check whether the application is already registered as a customer under the particular
            // APIprovider/Connected Account in Stripe
            monetizationSharedCustomer = stripeMonetizationDAO.getSharedCustomer(subWorkFlowDTO.getApplicationId(),
                    subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getTenantId());
            // Fetch plan details early so the currency is available for the Stripe Checkout Session
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getUuid(), con);
            String planId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(apiId, subWorkFlowDTO.getTierName());
            if (monetizationSharedCustomer.getSharedCustomerId() == null) {
                // checks whether the subscriber is already registered as a customer Under the
                // tenant/Platform account in Stripe
                monetizationPlatformCustomer = stripeMonetizationDAO.getPlatformCustomer(subscriber.getId(),
                        subscriber.getTenantId());
                if (monetizationPlatformCustomer.getCustomerId() == null) {
                    // New subscriber — no real card on file yet.
                    // Redirect to Stripe Checkout (setup mode) to collect a real payment method
                    // instead of using the test-only tok_visa token.
                    String currency = null;
                    if (StringUtils.isNotBlank(planId)) {
                        try {
                            currency = Plan.retrieve(planId, requestOptions).getCurrency();
                        } catch (StripeException e) {
                            throw new WorkflowException(
                                    "Failed to retrieve currency from Stripe plan : " + planId, e);
                        }
                    }
                    Session checkoutSession = createCheckoutSession(subscriber, subWorkFlowDTO, api.getUuid(), currency);

                    // Persist the session so the webhook handler can resume this workflow
                    stripeMonetizationDAO.saveCheckoutSession(
                            checkoutSession.getId(),
                            subWorkFlowDTO.getWorkflowReference(),
                            subscriber.getId(),
                            subWorkFlowDTO.getTenantId(),
                            api.getUuid(),
                            checkoutSession.getUrl());

                    // Store standard workflow properties (same as execute() would set)
                    workflowDTO.setProperties("apiName", subWorkFlowDTO.getApiName());
                    workflowDTO.setProperties("apiVersion", subWorkFlowDTO.getApiVersion());
                    workflowDTO.setProperties("subscriber", subWorkFlowDTO.getSubscriber());
                    workflowDTO.setProperties("applicationName", subWorkFlowDTO.getApplicationName());
                    // Store checkout URL so the REST API layer can surface it to the DevPortal
                    workflowDTO.setProperties(StripeMonetizationConstants.CHECKOUT_URL_PROPERTY,
                            checkoutSession.getUrl());
                    workflowDTO.setProperties(StripeMonetizationConstants.CHECKOUT_SESSION_ID_PROPERTY,
                            checkoutSession.getId());

                    // Leave workflow PENDING — complete() must NOT be called yet.
                    // It will be called by the webhook handler after card confirmation.
                    workflowDTO.setStatus(WorkflowStatus.CREATED);
                    super.execute(workflowDTO);

                    if (log.isDebugEnabled()) {
                        log.debug("Stripe Checkout Session " + checkoutSession.getId() +
                                " created for subscriber : " + subscriber.getName() +
                                ". Workflow reference : " + subWorkFlowDTO.getWorkflowReference() +
                                " is set to PENDING, awaiting card confirmation.");
                    }
                    HttpWorkflowResponse httpWorkflowResponse = new HttpWorkflowResponse();
                    httpWorkflowResponse.setRedirectUrl(checkoutSession.getUrl());
                    return httpWorkflowResponse;
                }
                // The platform customer may have been created via Stripe Checkout (payment_method)
                // rather than the legacy tok_visa / Token.create path. Token.create requires a
                // legacy source and fails for Checkout-created customers. Use the payment-method
                // path when the customer has a default payment method set.
                String defaultPmId = getDefaultPaymentMethodId(monetizationPlatformCustomer.getCustomerId());
                if (defaultPmId != null) {
                    monetizationSharedCustomer = createSharedCustomerWithPaymentMethod(
                            subscriber.getEmail(), monetizationPlatformCustomer, defaultPmId,
                            requestOptions, subWorkFlowDTO);
                } else {
                    monetizationSharedCustomer = createSharedCustomer(subscriber.getEmail(),
                            monetizationPlatformCustomer, requestOptions, subWorkFlowDTO);
                }
            }
            //creating Subscriptions
            createMonetizedSubscriptions(planId, monetizationSharedCustomer, requestOptions, subWorkFlowDTO, api.getUuid());
        } catch (APIManagementException e) {
            String errorMessage = "Could not monetize subscription for API : " + subWorkFlowDTO.getApiName()
                    + " by Application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Could not monetize subscription for API : " + subWorkFlowDTO.getApiName()
                    + " by Application " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new WorkflowException(errorMessage, e);
        }
        return execute(workflowDTO);
    }

    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, APIProduct apiProduct)
            throws WorkflowException {

        boolean isMonetizationEnabled = false;
        SubscriptionWorkflowDTO subWorkFlowDTO = null;
        String stripePlatformAccountKey = null;
        Subscriber subscriber = null;
        Customer customer = null;
        Customer sharedCustomerBE = null;
        MonetizationPlatformCustomer monetizationPlatformCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer;
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        //read the platform account key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());
        String connectedAccountKey = StringUtils.EMPTY;
        Map<String, String> monetizationProperties = new Gson().fromJson(apiProduct.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for : "
                        + apiProduct.getId().getName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to create artifacts in the stripe connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try {
            subscriber = apiMgtDAO.getSubscriber(subWorkFlowDTO.getSubscriber());
            // check whether the application is already registered as a customer under the particular
            // APIprovider/Connected Account in Stripe
            monetizationSharedCustomer = stripeMonetizationDAO.getSharedCustomer(subWorkFlowDTO.getApplicationId(),
                    subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getTenantId());
            // Fetch plan details early so the currency is available for the Stripe Checkout Session
            int apiId = ApiMgtDAO.getInstance().getAPIProductId(apiProduct.getId());
            String planId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(apiId, subWorkFlowDTO.getTierName());
            if (monetizationSharedCustomer.getSharedCustomerId() == null) {
                // checks whether the subscriber is already registered as a customer Under the
                // tenant/Platform account in Stripe
                monetizationPlatformCustomer = stripeMonetizationDAO.getPlatformCustomer(subscriber.getId(),
                        subscriber.getTenantId());
                if (monetizationPlatformCustomer.getCustomerId() == null) {
                    // New subscriber — redirect to Stripe Checkout to collect a real payment method
                    String currency = null;
                    if (StringUtils.isNotBlank(planId)) {
                        try {
                            currency = Plan.retrieve(planId, requestOptions).getCurrency();
                        } catch (StripeException e) {
                            throw new WorkflowException(
                                    "Failed to retrieve currency from Stripe plan : " + planId, e);
                        }
                    }
                    Session checkoutSession = createCheckoutSession(subscriber, subWorkFlowDTO,
                            apiProduct.getUuid(), currency);

                    stripeMonetizationDAO.saveCheckoutSession(
                            checkoutSession.getId(),
                            subWorkFlowDTO.getWorkflowReference(),
                            subscriber.getId(),
                            subWorkFlowDTO.getTenantId(),
                            apiProduct.getUuid(),
                            checkoutSession.getUrl());

                    workflowDTO.setProperties("apiName", subWorkFlowDTO.getApiName());
                    workflowDTO.setProperties("apiVersion", subWorkFlowDTO.getApiVersion());
                    workflowDTO.setProperties("subscriber", subWorkFlowDTO.getSubscriber());
                    workflowDTO.setProperties("applicationName", subWorkFlowDTO.getApplicationName());
                    workflowDTO.setProperties(StripeMonetizationConstants.CHECKOUT_URL_PROPERTY,
                            checkoutSession.getUrl());
                    workflowDTO.setProperties(StripeMonetizationConstants.CHECKOUT_SESSION_ID_PROPERTY,
                            checkoutSession.getId());

                    workflowDTO.setStatus(WorkflowStatus.CREATED);
                    super.execute(workflowDTO);

                    if (log.isDebugEnabled()) {
                        log.debug("Stripe Checkout Session " + checkoutSession.getId() +
                                " created for subscriber : " + subscriber.getName() +
                                ". Workflow reference : " + subWorkFlowDTO.getWorkflowReference() +
                                " is set to PENDING, awaiting card confirmation.");
                    }
                    HttpWorkflowResponse httpWorkflowResponse = new HttpWorkflowResponse();
                    httpWorkflowResponse.setRedirectUrl(checkoutSession.getUrl());
                    return httpWorkflowResponse;
                }
                // Same as the API overload: use payment-method path for Checkout-created customers
                String defaultPmId = getDefaultPaymentMethodId(monetizationPlatformCustomer.getCustomerId());
                if (defaultPmId != null) {
                    monetizationSharedCustomer = createSharedCustomerWithPaymentMethod(
                            subscriber.getEmail(), monetizationPlatformCustomer, defaultPmId,
                            requestOptions, subWorkFlowDTO);
                } else {
                    monetizationSharedCustomer = createSharedCustomer(subscriber.getEmail(),
                            monetizationPlatformCustomer, requestOptions, subWorkFlowDTO);
                }
            }
            //creating Subscriptions
            createMonetizedSubscriptions(planId, monetizationSharedCustomer, requestOptions, subWorkFlowDTO, apiProduct.getUuid());
        } catch (APIManagementException e) {
            String errorMessage = "Could not monetize subscription for : " + subWorkFlowDTO.getApiName()
                    + " by application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Could not monetize subscription for : " + subWorkFlowDTO.getApiName()
                    + " by application " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        }
        return execute(workflowDTO);
    }

    /**
     * Returns the stripe key of the platform/tenant
     *
     * @param tenantId id of the tenant
     * @return the stripe key of the platform/tenant
     * @throws WorkflowException
     */
    private String getPlatformAccountKey(int tenantId) throws WorkflowException {

        String stripePlatformAccountKey = null;
        String tenantDomain = APIUtil.getTenantDomainFromTenantId(tenantId);
        try {
            //get the stripe key of platform account from  tenant conf json file
            JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
            if (tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
                JSONObject monetizationInfo = (JSONObject) tenantConfig
                        .get(StripeMonetizationConstants.MONETIZATION_INFO);
                if (monetizationInfo.containsKey(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)) {
                    stripePlatformAccountKey = monetizationInfo
                            .get(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
                    if (StringUtils.isBlank(stripePlatformAccountKey)) {
                        String errorMessage = "Stripe platform account key is empty for tenant : " + tenantDomain;
                        throw new WorkflowException(errorMessage);
                    }
                    return stripePlatformAccountKey;
                }
            }
        } catch (APIManagementException e) {
            throw new WorkflowException("Failed to get the configuration for tenant from DB:  " + tenantDomain, e);
        }
        return stripePlatformAccountKey;
    }

    /**
     * Creates a Stripe Checkout Session in setup mode on the Platform Account.
     * The session collects the subscriber's real payment method via Stripe's hosted UI.
     * On completion, Stripe fires a checkout.session.completed webhook which the webhook
     * handler uses to resume the pending APIM workflow.
     *
     * <p>This replaces the previous approach of using the test-only DEFAULT_TOKEN (tok_visa).
     * The session is created on the platform account (no requestOptions) so the payment
     * method is stored at the platform level, consistent with the shared-customer architecture.
     *
     * @param subscriber     the APIM subscriber object
     * @param subWorkFlowDTO the subscription workflow DTO carrying context
     * @param apiUuid        UUID of the API or APIProduct being subscribed to
     * @return the created Stripe Checkout Session containing the redirect URL
     * @throws WorkflowException if the session cannot be created or URLs are not configured
     */
    private Session createCheckoutSession(Subscriber subscriber, SubscriptionWorkflowDTO subWorkFlowDTO,
            String apiUuid, String currency) throws WorkflowException {

        if (StringUtils.isBlank(checkoutSuccessUrl) || StringUtils.isBlank(checkoutCancelUrl)) {
            throw new WorkflowException(
                    "checkoutSuccessUrl and checkoutCancelUrl must be configured in workflow-extensions.xml " +
                    "for StripeSubscriptionCreationWorkflowExecutor");
        }
        try {
            // Build the success URL so it lands directly on the application's subscriptions tab.
            // checkoutSuccessUrl is expected to be the base applications URL
            // (e.g. https://host:9443/devportal/applications).
            // DevPortal routes use the application UUID, not the numeric DB ID, so we look
            // up the UUID from AM_APPLICATION before building the path.
            String applicationUUID = getApplicationUUID(subWorkFlowDTO.getApplicationId());
            String appPath = applicationUUID != null
                    ? applicationUUID
                    : String.valueOf(subWorkFlowDTO.getApplicationId());
            String successUrl = checkoutSuccessUrl
                    + "/" + appPath
                    + "/subscriptions?session_id={CHECKOUT_SESSION_ID}";

            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SETUP)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(checkoutCancelUrl)
                    // currency is required by newer Stripe API versions for setup mode sessions
                    .setCurrency(currency)
                    // Core identifiers — used by the webhook to resume the workflow
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_WORKFLOW_REF,
                            subWorkFlowDTO.getWorkflowReference())
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_SUBSCRIBER_ID,
                            String.valueOf(subscriber.getId()))
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_TENANT_ID,
                            String.valueOf(subWorkFlowDTO.getTenantId()))
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_API_UUID, apiUuid)
                    // Subscription context — stored here so complete() needs no extra DB lookup
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_APPLICATION_ID,
                            String.valueOf(subWorkFlowDTO.getApplicationId()))
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_TIER_NAME,
                            subWorkFlowDTO.getTierName())
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_API_PROVIDER,
                            subWorkFlowDTO.getApiProvider())
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_APPLICATION_NAME,
                            subWorkFlowDTO.getApplicationName())
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_API_NAME,
                            subWorkFlowDTO.getApiName())
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_API_VERSION,
                            subWorkFlowDTO.getApiVersion());

            // Pre-fill subscriber email on the Stripe-hosted checkout form if available
            if (!StringUtils.isEmpty(subscriber.getEmail())) {
                paramsBuilder.setCustomerEmail(subscriber.getEmail());
            }
            // Note: Session.create() without requestOptions targets the platform account (Stripe.apiKey)
            // This is intentional — the payment method must live on the platform account
            return Session.create(paramsBuilder.build());

        } catch (StripeException e) {
            String errorMsg = "Failed to create Stripe Checkout Session for subscriber : " +
                    subscriber.getName() + " on workflow reference : " + subWorkFlowDTO.getWorkflowReference();
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }

    /**
     * The method creates a Shared Customer in billing engine
     *
     * @param email            Email of the subscriber
     * @param platformCustomer Monetization customer details created under platform account
     * @param requestOptions   contains credentials to make api requests on behalf of the connected account
     * @param subWorkFlowDTO   The WorkflowDTO which contains workflow contextual information related to the workflow
     * @return MonetizationSharedCustomer Object with the details of the created shared customer
     * @throws WorkflowException
     */
    public MonetizationSharedCustomer createSharedCustomer(String email, MonetizationPlatformCustomer platformCustomer,
                                                           RequestOptions requestOptions,
                                                           SubscriptionWorkflowDTO subWorkFlowDTO)
            throws WorkflowException {

        Customer stripeCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer = new MonetizationSharedCustomer();
        Token token;
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put(StripeMonetizationConstants.CUSTOMER, platformCustomer.getCustomerId());
            //creating a token using the platform customers source
            token = Token.create(params, requestOptions);
        } catch (StripeException ex) {
            String errorMsg = "Error when creating a stripe token for" + platformCustomer.getSubscriberName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        Map<String, Object> sharedCustomerParams = new HashMap<>();
        //if the email id of subscriber is empty, a customer object in billing engine will be created without email id
        if (!StringUtils.isEmpty(email)) {
            sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_EMAIL, email);
        }
        try {
            sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_DESCRIPTION, "Shared Customer for "
                    + subWorkFlowDTO.getApplicationName() + StripeMonetizationConstants.FILE_SEPERATOR
                    + subWorkFlowDTO.getSubscriber());
            sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_SOURCE, token.getId());
            stripeCustomer = Customer.create(sharedCustomerParams, requestOptions);
            try {
                monetizationSharedCustomer.setApplicationId(subWorkFlowDTO.getApplicationId());
                monetizationSharedCustomer.setApiProvider(subWorkFlowDTO.getApiProvider());
                monetizationSharedCustomer.setTenantId(subWorkFlowDTO.getTenantId());
                monetizationSharedCustomer.setSharedCustomerId(stripeCustomer.getId());
                monetizationSharedCustomer.setParentCustomerId(platformCustomer.getId());
                //returns the ID of the inserted record
                int id = stripeMonetizationDAO.addBESharedCustomer(monetizationSharedCustomer);
                monetizationSharedCustomer.setId(id);
            } catch (StripeMonetizationException ex) {
                //deleting the created customer in stripe if it fails to create the DB record
                stripeCustomer.delete(requestOptions);
                String errorMsg = "Error when inserting Stripe shared customer details of Application : "
                        + subWorkFlowDTO.getApplicationName() + "to database";
                log.error(errorMsg, ex);
                throw new WorkflowException(errorMsg, ex);
            }
            if (log.isDebugEnabled()) {
                String msg = "A customer for Application " + subWorkFlowDTO.getApplicationName()
                        + " is created under the " + subWorkFlowDTO.getApiProvider()
                        + "'s connected account in Stripe";
                log.debug(msg);
            }
        } catch (StripeException ex) {
            String errorMsg = "Error while creating a shared customer in Stripe for Application : "
                    + subWorkFlowDTO.getApplicationName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationSharedCustomer;
    }

    /**
     * The method creates a subscription in Billing Engine
     *
     * @param planId         plan Id of the Stripe monetization plan
     * @param sharedCustomer contains info about the customer created in the provider account of Stripe
     * @param requestOptions contains connected account credential needed for Stripe transactions
     * @param subWorkFlowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @throws WorkflowException
     */
    public void createMonetizedSubscriptions(String planId, MonetizationSharedCustomer sharedCustomer,
            RequestOptions requestOptions, SubscriptionWorkflowDTO subWorkFlowDTO, String apiUuid)
            throws WorkflowException {

        StripeMonetizationDAO stripeMonetizationDAO = StripeMonetizationDAO.getInstance();
        APIIdentifier identifier = new APIIdentifier(subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getApiName(),
                subWorkFlowDTO.getApiVersion());
        Subscription subscription = null;
        try {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put(StripeMonetizationConstants.PLAN, planId);
            Map<String, Object> items = new HashMap<String, Object>();
            //adding a subscription item, with an attached plan.
            items.put("0", item);
            Map<String, Object> subParams = new HashMap<String, Object>();
            subParams.put(StripeMonetizationConstants.CUSTOMER, sharedCustomer.getSharedCustomerId());
            subParams.put(StripeMonetizationConstants.ITEMS, items);
            try {
                //create a subscription in stripe under the API Providers Connected Account
                subscription = Subscription.create(subParams, requestOptions);
            } catch (StripeException ex) {
                String errorMsg = "Error when adding a subscription in Stripe for Application : " +
                        subWorkFlowDTO.getApplicationName();
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, ex);
            }
            try {
                stripeMonetizationDAO.addBESubscription(identifier, subWorkFlowDTO.getApplicationId(),
                        subWorkFlowDTO.getTenantId(), sharedCustomer.getId(), subscription.getId(), apiUuid);
            } catch (StripeMonetizationException e) {
                //delete the subscription in Stripe, if the entry to database fails in API Manager
                subscription.cancel((Map<String, Object>) null, requestOptions);
                String errorMsg = "Error when adding stripe subscription details of Application "
                        + subWorkFlowDTO.getApplicationName() + " to Database";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, e);
            }
            if (log.isDebugEnabled()) {
                String msg = "Stripe subscription for " + subWorkFlowDTO.getApplicationName() + " is created for"
                        + subWorkFlowDTO.getApiName() + " API";
                log.debug(msg);
            }
        } catch (StripeException ex) {
            String errorMessage = "Failed to create subscription in Stripe for API : " + subWorkFlowDTO.getApiName()
                    + "by Application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, ex);
        }
    }

    /**
     * The method creates a Platform Customer in Billing Engine
     *
     * @param subscriber object which contains info about the subscriber
     * @return StripeCustomer object which contains info about the customer created in platform account of stripe
     * @throws WorkflowException
     */
    public MonetizationPlatformCustomer createMonetizationPlatformCutomer(Subscriber subscriber)
            throws WorkflowException {

        MonetizationPlatformCustomer monetizationPlatformCustomer = new MonetizationPlatformCustomer();
        Customer customer = null;
        try {
            Map<String, Object> customerParams = new HashMap<String, Object>();
            //Customer object in billing engine will be created without the email id
            if (!StringUtils.isEmpty(subscriber.getEmail())) {
                customerParams.put(StripeMonetizationConstants.CUSTOMER_EMAIL, subscriber.getEmail());
            }
            customerParams.put(StripeMonetizationConstants.CUSTOMER_DESCRIPTION, "Customer for "
                    + subscriber.getName());
            customerParams.put(StripeMonetizationConstants.CUSTOMER_SOURCE, StripeMonetizationConstants.DEFAULT_TOKEN);
            //create a customer for subscriber in the platform account
            customer = Customer.create(customerParams);
            monetizationPlatformCustomer.setCustomerId(customer.getId());
            try {
                //returns the id of the inserted record
                int id = stripeMonetizationDAO.addBEPlatformCustomer(subscriber.getId(), subscriber.getTenantId(),
                        customer.getId());
                monetizationPlatformCustomer.setId(id);
            } catch (StripeMonetizationException e) {
                if (customer != null) {
                    // deletes the customer if the customer is created in Stripe and failed to update in DB
                    customer.delete();
                }
                String errorMsg = "Error when inserting stripe customer details of " + subscriber.getName()
                        + " to Database";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, e);
            }
        } catch (StripeException ex) {
            String errorMsg = "Error while creating a customer in Stripe for " + subscriber.getName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationPlatformCustomer;
    }

    /**
     * This method completes subscription creation workflow and return workflow response back to the caller.
     *
     * <p>Two completion paths are supported:
     * <ul>
     *   <li><b>Checkout path</b> — triggered by the Stripe webhook webapp when a subscriber
     *       finishes card entry on Stripe Checkout. The webhook handler sets
     *       {@code workflowDTO.attributes["checkoutSessionId"]} before calling this method.
     *       All Stripe customer and subscription creation happens here.</li>
     *   <li><b>Direct path</b> — existing behaviour when monetization skips the Checkout step
     *       (platform customer already exists). Stripe work was already done in
     *       {@code monetizeSubscription()} and this method simply unblocks the subscription.</li>
     * </ul>
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information
     * @return workflow response back to the caller
     * @throws WorkflowException if the workflow cannot be completed
     */
    @Override
    public WorkflowResponse complete(WorkflowDTO workflowDTO) throws WorkflowException {

        // ── Checkout-triggered path ──────────────────────────────────────────
        // The webhook webapp sets this attribute before calling complete().
        String checkoutSessionId = workflowDTO.getAttributes().get(
                StripeMonetizationConstants.CHECKOUT_SESSION_ID_ATTRIBUTE);
        if (!StringUtils.isBlank(checkoutSessionId)) {
            // Idempotency guard: atomically claim the session (PENDING → IN_PROGRESS).
            // Both the Stripe webhook path and the browser-redirect path call complete()
            // concurrently. Only the one that wins the DB claim proceeds with Stripe work;
            // the other sees false and skips to the standard DB update below.
            boolean claimed;
            try {
                claimed = stripeMonetizationDAO.claimCheckoutSession(checkoutSessionId);
            } catch (StripeMonetizationException e) {
                throw new WorkflowException("Failed to claim checkout session: " + checkoutSessionId, e);
            }
            if (claimed) {
                try {
                    completeStripeCheckoutSubscription(workflowDTO, checkoutSessionId);
                } catch (WorkflowException e) {
                    // Reset the claim so the other path (webhook or browser-redirect) can retry.
                    try {
                        stripeMonetizationDAO.resetCheckoutSessionClaim(checkoutSessionId);
                    } catch (StripeMonetizationException resetEx) {
                        log.error("Failed to reset claim for session " + checkoutSessionId
                                + " after completion error", resetEx);
                    }
                    throw e;
                }
            } else {
                log.info("Stripe checkout session " + checkoutSessionId
                        + " already claimed or completed — skipping Stripe work in this path");
            }
        }

        // ── Standard workflow DB update ──────────────────────────────────────
        workflowDTO.setUpdatedTime(System.currentTimeMillis());
        super.complete(workflowDTO);  // persists WF_STATUS in AM_WORKFLOWS

        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        try {
            if (WorkflowStatus.APPROVED.equals(workflowDTO.getStatus())) {
                apiMgtDAO.updateSubscriptionStatus(
                        Integer.parseInt(workflowDTO.getWorkflowReference()),
                        APIConstants.SubscriptionStatus.UNBLOCKED);
            }
        } catch (APIManagementException e) {
            log.error("Could not complete subscription creation workflow", e);
            throw new WorkflowException("Could not complete subscription creation workflow", e);
        }
        return new GeneralWorkflowResponse();
    }

    // =========================================================================
    // Checkout-based completion helpers
    // =========================================================================

    /**
     * Performs all Stripe API calls needed to complete a subscription that was initiated
     * via Stripe Checkout (setup mode). Called only when {@code workflowDTO.attributes}
     * contains {@code checkoutSessionId}.
     *
     * <p>Steps:
     * <ol>
     *   <li>Retrieve the Stripe Checkout Session and its SetupIntent payment method.</li>
     *   <li>Create a Stripe Platform Customer with the real payment method.</li>
     *   <li>Clone the payment method to the connected account and create a Shared Customer.</li>
     *   <li>Create the Stripe Subscription.</li>
     *   <li>Mark the checkout session as COMPLETED in {@code AM_STRIPE_CHECKOUT_SESSIONS}.</li>
     * </ol>
     */
    private void completeStripeCheckoutSubscription(WorkflowDTO workflowDTO, String checkoutSessionId)
            throws WorkflowException {

        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();

        try {
            // 1. Set platform account API key
            Stripe.apiKey = getPlatformAccountKey(workflowDTO.getTenantId());

            // 2. Retrieve Stripe Checkout Session and the payment method from its SetupIntent
            Session session = Session.retrieve(checkoutSessionId);
            SetupIntent setupIntent = SetupIntent.retrieve(session.getSetupIntent());
            String paymentMethodId = setupIntent.getPaymentMethod();

            // 3. Load the APIM subscriber via the ID stored in session metadata
            int subscriberId = Integer.parseInt(
                    session.getMetadata().get(StripeMonetizationConstants.CHECKOUT_METADATA_SUBSCRIBER_ID));
            Subscriber subscriber = apiMgtDAO.getSubscriber(subscriberId);

            // 4. Read all subscription context from session metadata (avoids DB lookup)
            Map<String, String> meta = session.getMetadata();
            String apiUuid = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_UUID);
            int applicationId = Integer.parseInt(meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_APPLICATION_ID));
            String tierName = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_TIER_NAME);
            String apiProvider = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_PROVIDER);
            String applicationName = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_APPLICATION_NAME);
            String apiName = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_NAME);
            String apiVersion = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_VERSION);

            // 5. Build a lightweight SubscriptionWorkflowDTO for the downstream helper methods
            SubscriptionWorkflowDTO subWorkflowDTO = new SubscriptionWorkflowDTO();
            subWorkflowDTO.setApplicationId(applicationId);
            subWorkflowDTO.setApiProvider(apiProvider);
            subWorkflowDTO.setTierName(tierName);
            subWorkflowDTO.setSubscriber(subscriber.getName());
            subWorkflowDTO.setTenantId(workflowDTO.getTenantId());
            subWorkflowDTO.setApplicationName(applicationName);
            subWorkflowDTO.setApiName(apiName);
            subWorkflowDTO.setApiVersion(apiVersion);

            // 6. Get the connected (provider) Stripe account key
            String connectedAccountKey = getConnectedAccountKey(apiUuid, workflowDTO.getTenantDomain());
            RequestOptions requestOptions = RequestOptions.builder()
                    .setStripeAccount(connectedAccountKey).build();

            // 7. Create a Stripe Platform Customer with the real payment method
            MonetizationPlatformCustomer platformCustomer =
                    createPlatformCustomerWithPaymentMethod(subscriber, paymentMethodId);

            // 8. Clone the payment method to the connected account and create a Shared Customer
            MonetizationSharedCustomer sharedCustomer =
                    createSharedCustomerWithPaymentMethod(
                            subscriber.getEmail(), platformCustomer, paymentMethodId,
                            requestOptions, subWorkflowDTO);

            // 9. Resolve billing plan and create the Stripe Subscription
            try (Connection con = APIMgtDBUtil.getConnection()) {
                int apiId = ApiMgtDAO.getInstance().getAPIID(apiUuid, con);
                String planId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(apiId, tierName);
                createMonetizedSubscriptions(planId, sharedCustomer, requestOptions,
                        subWorkflowDTO, apiUuid);
            }

            // 10. Mark the checkout session record as completed
            stripeMonetizationDAO.updateCheckoutSessionStatus(
                    checkoutSessionId, StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_COMPLETED);

            log.info("Stripe Checkout subscription completed for subscriber=" + subscriber.getName()
                    + " session=" + checkoutSessionId);

        } catch (StripeException e) {
            throw new WorkflowException("Stripe API error during checkout completion for session: "
                    + checkoutSessionId, e);
        } catch (APIManagementException e) {
            throw new WorkflowException("APIM error during checkout completion for session: "
                    + checkoutSessionId, e);
        } catch (StripeMonetizationException e) {
            throw new WorkflowException("Monetization DB error during checkout completion for session: "
                    + checkoutSessionId, e);
        } catch (SQLException e) {
            throw new WorkflowException("DB error during checkout completion for session: "
                    + checkoutSessionId, e);
        }
    }

    /**
     * Returns the Stripe connected account key from the API's monetization properties.
     * Initialises {@link #apiPersistenceInstance} if not already set.
     */
    private String getConnectedAccountKey(String apiUuid, String tenantDomain) throws WorkflowException {
        if (apiPersistenceInstance == null) {
            Properties props = new Properties();
            props.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
            props.put(APIConstants.ALLOW_MULTIPLE_VERSIONS, APIUtil.isAllowDisplayMultipleVersions());
            Map<String, String> configMap = new HashMap<>();
            Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
            if (configs != null && !configs.isEmpty()) {
                configMap.putAll(configs);
            }
            configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                    Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
            apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap, props);
        }
        try {
            Organization org = new Organization(tenantDomain);
            PublisherAPI publisherAPI = apiPersistenceInstance.getPublisherAPI(org, apiUuid);
            Map<String, String> monetizationProperties = new Gson().fromJson(
                    publisherAPI.getMonetizationProperties().toString(), HashMap.class);
            if (MapUtils.isNotEmpty(monetizationProperties) && monetizationProperties
                    .containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                String key = monetizationProperties.get(
                        StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
                if (StringUtils.isBlank(key)) {
                    throw new WorkflowException(
                            "Connected account Stripe key is empty for API: " + apiUuid);
                }
                return key;
            }
        } catch (APIPersistenceException e) {
            throw new WorkflowException(
                    "Failed to retrieve API monetization properties for API: " + apiUuid, e);
        }
        throw new WorkflowException(
                "Connected account Stripe key not found for API: " + apiUuid);
    }

    /**
     * Looks up the application UUID from the numeric {@code APPLICATION_ID} stored in
     * {@code AM_APPLICATION}. The DevPortal uses UUIDs in its routes
     * ({@code /devportal/applications/{UUID}/subscriptions}), not integer IDs.
     *
     * @param applicationId numeric application DB ID from {@link SubscriptionWorkflowDTO#getApplicationId()}
     * @return the UUID string, or {@code null} if the lookup fails (the numeric ID is used as fallback)
     */
    private String getApplicationUUID(int applicationId) {
        Connection conn = null;
        java.sql.PreparedStatement ps = null;
        java.sql.ResultSet rs = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(
                    "SELECT UUID FROM AM_APPLICATION WHERE APPLICATION_ID = ?");
            ps.setInt(1, applicationId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("UUID");
            }
        } catch (java.sql.SQLException e) {
            log.warn("Could not retrieve UUID for applicationId " + applicationId
                    + " — numeric ID will be used in the Stripe success URL", e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return null;
    }

    /**
     * Returns the {@code invoice_settings.default_payment_method} for a Stripe platform
     * customer, or {@code null} if the customer was created via the legacy source/token path
     * (or if the Stripe retrieval fails).
     *
     * <p>Platform customers created via Stripe Checkout have a {@code payment_method} set
     * as their invoice default. Legacy customers (created with {@code tok_visa}) have a
     * {@code source} instead and return {@code null} here, preserving the old Token flow.
     */
    private String getDefaultPaymentMethodId(String stripeCustomerId) {
        try {
            Customer customer = Customer.retrieve(stripeCustomerId);
            if (customer != null && customer.getInvoiceSettings() != null) {
                return customer.getInvoiceSettings().getDefaultPaymentMethod();
            }
        } catch (StripeException e) {
            log.warn("Failed to retrieve default payment method for Stripe customer: "
                    + stripeCustomerId + " — falling back to legacy Token-based flow", e);
        }
        return null;
    }

    /**
     * Creates a Stripe Platform Customer attached to a real payment method collected via
     * Stripe Checkout (replaces the legacy {@code tok_visa} approach in
     * {@link #createMonetizationPlatformCutomer}).
     */
    private MonetizationPlatformCustomer createPlatformCustomerWithPaymentMethod(
            Subscriber subscriber, String paymentMethodId) throws WorkflowException {

        MonetizationPlatformCustomer monetizationPlatformCustomer = new MonetizationPlatformCustomer();
        try {
            Map<String, Object> customerParams = new HashMap<>();
            if (!StringUtils.isEmpty(subscriber.getEmail())) {
                customerParams.put(StripeMonetizationConstants.CUSTOMER_EMAIL, subscriber.getEmail());
            }
            customerParams.put(StripeMonetizationConstants.CUSTOMER_DESCRIPTION,
                    "Customer for " + subscriber.getName());
            customerParams.put("payment_method", paymentMethodId);
            // Make the collected payment method the default for future invoices
            Map<String, Object> invoiceSettings = new HashMap<>();
            invoiceSettings.put("default_payment_method", paymentMethodId);
            customerParams.put("invoice_settings", invoiceSettings);

            Customer customer = Customer.create(customerParams);
            monetizationPlatformCustomer.setCustomerId(customer.getId());

            int id = stripeMonetizationDAO.addBEPlatformCustomer(
                    subscriber.getId(), subscriber.getTenantId(), customer.getId());
            monetizationPlatformCustomer.setId(id);

        } catch (StripeException ex) {
            String errorMsg = "Error creating Stripe platform customer for: " + subscriber.getName();
            log.error(errorMsg, ex);
            throw new WorkflowException(errorMsg, ex);
        } catch (StripeMonetizationException ex) {
            String errorMsg = "Error saving platform customer to DB for: " + subscriber.getName();
            log.error(errorMsg, ex);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationPlatformCustomer;
    }

    /**
     * Creates a Shared Customer on the API provider's connected Stripe account by cloning
     * the payment method from the platform account.
     *
     * <p>This is the modern alternative to {@link #createSharedCustomer} (which uses the
     * legacy {@code Token.create} API). When the platform customer was created via Stripe
     * Checkout, only a {@code payment_method} (not a {@code source}/card) is attached, so
     * the token-based approach does not apply.
     */
    private MonetizationSharedCustomer createSharedCustomerWithPaymentMethod(
            String email, MonetizationPlatformCustomer platformCustomer, String paymentMethodId,
            RequestOptions requestOptions, SubscriptionWorkflowDTO subWorkFlowDTO)
            throws WorkflowException {

        MonetizationSharedCustomer monetizationSharedCustomer = new MonetizationSharedCustomer();
        Customer stripeCustomer = null;
        try {
            // Clone the payment method from the platform account to the connected account
            Map<String, Object> pmParams = new HashMap<>();
            pmParams.put("customer", platformCustomer.getCustomerId());
            pmParams.put("payment_method", paymentMethodId);
            PaymentMethod clonedPm = PaymentMethod.create(pmParams, requestOptions);

            // Create customer on the connected account with the cloned payment method
            Map<String, Object> sharedCustomerParams = new HashMap<>();
            if (!StringUtils.isEmpty(email)) {
                sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_EMAIL, email);
            }
            sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_DESCRIPTION,
                    "Shared Customer for " + subWorkFlowDTO.getApplicationName()
                    + StripeMonetizationConstants.FILE_SEPERATOR + subWorkFlowDTO.getSubscriber());
            sharedCustomerParams.put("payment_method", clonedPm.getId());
            // Set the cloned payment method as the default for invoices/subscriptions.
            // Without this Stripe rejects Subscription.create with "no attached payment method".
            Map<String, Object> invoiceSettings = new HashMap<>();
            invoiceSettings.put("default_payment_method", clonedPm.getId());
            sharedCustomerParams.put("invoice_settings", invoiceSettings);

            stripeCustomer = Customer.create(sharedCustomerParams, requestOptions);

            try {
                monetizationSharedCustomer.setApplicationId(subWorkFlowDTO.getApplicationId());
                monetizationSharedCustomer.setApiProvider(subWorkFlowDTO.getApiProvider());
                monetizationSharedCustomer.setTenantId(subWorkFlowDTO.getTenantId());
                monetizationSharedCustomer.setSharedCustomerId(stripeCustomer.getId());
                monetizationSharedCustomer.setParentCustomerId(platformCustomer.getId());
                int id = stripeMonetizationDAO.addBESharedCustomer(monetizationSharedCustomer);
                monetizationSharedCustomer.setId(id);
            } catch (StripeMonetizationException ex) {
                stripeCustomer.delete(requestOptions);
                String errorMsg = "Error saving shared customer to DB for application: "
                        + subWorkFlowDTO.getApplicationName();
                log.error(errorMsg, ex);
                throw new WorkflowException(errorMsg, ex);
            }
        } catch (StripeException ex) {
            String errorMsg = "Error creating shared customer in Stripe for application: "
                    + subWorkFlowDTO.getApplicationName();
            log.error(errorMsg, ex);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationSharedCustomer;
    }

}
