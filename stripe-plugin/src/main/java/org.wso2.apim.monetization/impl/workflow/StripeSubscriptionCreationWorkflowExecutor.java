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
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.apim.monetization.impl.StripeMonetizationDAO;
import org.wso2.apim.monetization.impl.StripeMonetizationException;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Workflow executor for Stripe-based subscription creation.
 *
 * <p>Every new API subscription — regardless of whether the subscriber has used Stripe
 * before — is routed through a Stripe-hosted Checkout page in {@code subscription} mode
 * on the API provider's connected Stripe account. Users see the plan name, price, billing
 * cycle, and available payment methods before the subscription is activated. No payment
 * method is ever reused silently.
 *
 * <p>Flow overview:
 * <ol>
 *   <li>{@link #monetizeSubscription} creates a Stripe Checkout Session and returns an
 *       {@link HttpWorkflowResponse} with the redirect URL. The APIM workflow is left in
 *       {@code CREATED} (pending) state.</li>
 *   <li>After the user completes checkout, Stripe fires {@code checkout.session.completed}
 *       webhook <em>and</em> the browser lands on the success URL carrying
 *       {@code ?session_id=cs_xxx}. Both paths call {@link #complete}; a DB-level
 *       idempotency guard (PENDING → IN_PROGRESS) ensures exactly one path processes
 *       the session.</li>
 *   <li>{@link #complete} delegates to {@link #completeStripeCheckoutSubscription} which
 *       persists the Stripe-created subscription and activates the APIM subscription.</li>
 * </ol>
 *
 * <p><strong>Schema prerequisite:</strong> The FK constraint
 * {@code am_monetization_shared_customers_ibfk_2} (PARENT_CUSTOMER_ID →
 * AM_MONETIZATION_PLATFORM_CUSTOMERS.ID) must be dropped before deploying this executor.
 * Run the migration in {@code docs/per-subscription-checkout-design.md §DB Migration}.
 */
public class StripeSubscriptionCreationWorkflowExecutor extends WorkflowExecutor {

    private static final Log log = LogFactory.getLog(StripeSubscriptionCreationWorkflowExecutor.class);
    StripeMonetizationDAO stripeMonetizationDAO = StripeMonetizationDAO.getInstance();
    APIPersistence apiPersistenceInstance;

    /**
     * DevPortal base URL for the applications page. The success URL is built as:
     * {@code {checkoutSuccessUrl}/{applicationUUID}/subscriptions?session_id={CHECKOUT_SESSION_ID}}
     *
     * <p>Set in workflow-extensions.xml:
     * <pre>{@code <Property name="checkoutSuccessUrl">https://host:9443/devportal/applications</Property>}</pre>
     */
    private String checkoutSuccessUrl;

    /**
     * DevPortal URL to send the user to if they cancel on Stripe Checkout.
     *
     * <p>Set in workflow-extensions.xml:
     * <pre>{@code <Property name="checkoutCancelUrl">https://host:9443/devportal/applications</Property>}</pre>
     */
    private String checkoutCancelUrl;

    public void setCheckoutSuccessUrl(String checkoutSuccessUrl) {
        this.checkoutSuccessUrl = checkoutSuccessUrl;
    }

    public void setCheckoutCancelUrl(String checkoutCancelUrl) {
        this.checkoutCancelUrl = checkoutCancelUrl;
    }

    // =========================================================================
    // WorkflowExecutor contract
    // =========================================================================

    @Override
    public String getWorkflowType() {
        return WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION;
    }

    @Override
    public List<WorkflowDTO> getWorkflowDetails(String workflowStatus) throws WorkflowException {
        return null;
    }

    /**
     * Standard (non-monetized) execute path. Also used as the final activation step when
     * the caller has already performed all Stripe work and simply needs to unblock the APIM
     * subscription.
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
        return complete(workflowDTO);
    }

    // =========================================================================
    // monetizeSubscription — API
    // =========================================================================

    /**
     * Executes Stripe monetization for a new API subscription.
     *
     * <p>Unconditionally creates a Stripe Checkout Session in {@code subscription} mode on
     * the API provider's connected Stripe account. The user must explicitly confirm a
     * payment method and see the pricing before the APIM subscription is activated.
     *
     * <p>The workflow is left in {@code CREATED} (pending) state. Activation happens only
     * after {@link #complete} is called by either the {@code checkout.session.completed}
     * webhook handler or the browser-redirect complete-session endpoint.
     *
     * @param workflowDTO the workflow context carrying subscription metadata
     * @param api         the monetized API being subscribed to
     * @return an {@link HttpWorkflowResponse} carrying the Stripe Checkout redirect URL
     * @throws WorkflowException if any Stripe API call or configuration lookup fails
     */
    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, API api)
            throws WorkflowException {

        SubscriptionWorkflowDTO subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;

        // Set the platform Stripe API key for this tenant
        Stripe.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());

        // Resolve connected account key — also initialises apiPersistenceInstance lazily
        String connectedAccountKey = getConnectedAccountKey(api.getUuid(), workflowDTO.getTenantDomain());
        RequestOptions requestOptions = RequestOptions.builder()
                .setStripeAccount(connectedAccountKey).build();

        // Resolve the APIM subscriber
        Subscriber subscriber;
        try {
            subscriber = ApiMgtDAO.getInstance().getSubscriber(subWorkFlowDTO.getSubscriber());
        } catch (APIManagementException e) {
            throw new WorkflowException(
                    "Failed to retrieve subscriber: " + subWorkFlowDTO.getSubscriber(), e);
        }

        // Resolve the Stripe price ID for the selected throttling tier
        String priceId;
        try (Connection con = APIMgtDBUtil.getConnection()) {
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getUuid(), con);
            priceId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(
                    apiId, subWorkFlowDTO.getTierName());
        } catch (APIManagementException e) {
            throw new WorkflowException(
                    "Failed to retrieve API DB ID for UUID: " + api.getUuid(), e);
        } catch (SQLException e) {
            throw new WorkflowException(
                    "DB connection error resolving API ID for UUID: " + api.getUuid(), e);
        } catch (StripeMonetizationException e) {
            throw new WorkflowException(
                    "Failed to retrieve billing plan for tier: " + subWorkFlowDTO.getTierName(), e);
        }

        if (StringUtils.isBlank(priceId)) {
            throw new WorkflowException("No Stripe price ID found for tier '"
                    + subWorkFlowDTO.getTierName() + "' on API: " + api.getId().getApiName());
        }

        // Always create a new Checkout Session — no silent payment method reuse
        Session checkoutSession = createCheckoutSession(
                subscriber, subWorkFlowDTO, api.getUuid(), priceId, requestOptions);

        try {
            stripeMonetizationDAO.saveCheckoutSession(
                    checkoutSession.getId(),
                    subWorkFlowDTO.getWorkflowReference(),
                    subscriber.getId(),
                    subWorkFlowDTO.getTenantId(),
                    api.getUuid(),
                    checkoutSession.getUrl());
        } catch (StripeMonetizationException e) {
            throw new WorkflowException("Failed to persist checkout session for workflow: "
                    + subWorkFlowDTO.getWorkflowReference(), e);
        }

        workflowDTO.setProperties("apiName", subWorkFlowDTO.getApiName());
        workflowDTO.setProperties("apiVersion", subWorkFlowDTO.getApiVersion());
        workflowDTO.setProperties("subscriber", subWorkFlowDTO.getSubscriber());
        workflowDTO.setProperties("applicationName", subWorkFlowDTO.getApplicationName());
        workflowDTO.setProperties(StripeMonetizationConstants.CHECKOUT_URL_PROPERTY,
                checkoutSession.getUrl());
        workflowDTO.setProperties(StripeMonetizationConstants.CHECKOUT_SESSION_ID_PROPERTY,
                checkoutSession.getId());

        // Leave the workflow PENDING — complete() must not be called until Stripe confirms payment
        workflowDTO.setStatus(WorkflowStatus.CREATED);
        super.execute(workflowDTO);

        if (log.isDebugEnabled()) {
            log.debug("Stripe Checkout Session created: subscriber=" + subscriber.getName()
                    + " API=" + api.getId().getApiName()
                    + " workflowRef=" + subWorkFlowDTO.getWorkflowReference());
        }

        HttpWorkflowResponse httpWorkflowResponse = new HttpWorkflowResponse();
        httpWorkflowResponse.setRedirectUrl(checkoutSession.getUrl());
        return httpWorkflowResponse;
    }

    // =========================================================================
    // monetizeSubscription — APIProduct
    // =========================================================================

    /**
     * Executes Stripe monetization for a new API product subscription.
     *
     * <p>Symmetric to {@link #monetizeSubscription(WorkflowDTO, API)}. The connected account
     * key is read directly from the {@code APIProduct} object rather than from the API
     * persistence layer, which does not handle product UUIDs via {@code getPublisherAPI()}.
     *
     * @param workflowDTO the workflow context
     * @param apiProduct  the monetized API product being subscribed to
     * @return an {@link HttpWorkflowResponse} carrying the Stripe Checkout redirect URL
     * @throws WorkflowException if any Stripe API call or configuration lookup fails
     */
    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, APIProduct apiProduct)
            throws WorkflowException {

        SubscriptionWorkflowDTO subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;

        // Set the platform Stripe API key for this tenant
        Stripe.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());

        // Read connected account key directly from the API product's monetization properties
        Map<String, String> monetizationProperties = new Gson().fromJson(
                apiProduct.getMonetizationProperties().toString(), HashMap.class);
        String connectedAccountKey;
        if (MapUtils.isNotEmpty(monetizationProperties) && monetizationProperties.containsKey(
                StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get(
                    StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                throw new WorkflowException(
                        "Connected account Stripe key is empty for API product: "
                        + apiProduct.getId().getName());
            }
        } else {
            throw new WorkflowException(
                    "Connected account Stripe key not found in monetization properties of API product: "
                    + apiProduct.getId().getName());
        }
        RequestOptions requestOptions = RequestOptions.builder()
                .setStripeAccount(connectedAccountKey).build();

        // Resolve the APIM subscriber
        Subscriber subscriber;
        try {
            subscriber = ApiMgtDAO.getInstance().getSubscriber(subWorkFlowDTO.getSubscriber());
        } catch (APIManagementException e) {
            throw new WorkflowException(
                    "Failed to retrieve subscriber: " + subWorkFlowDTO.getSubscriber(), e);
        }

        // Resolve the Stripe price ID for the selected throttling tier
        String priceId;
        try {
            int apiId = ApiMgtDAO.getInstance().getAPIProductId(apiProduct.getId());
            priceId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(
                    apiId, subWorkFlowDTO.getTierName());
        } catch (APIManagementException e) {
            throw new WorkflowException(
                    "Failed to retrieve API product DB ID for: " + apiProduct.getId().getName(), e);
        } catch (StripeMonetizationException e) {
            throw new WorkflowException(
                    "Failed to retrieve billing plan for tier: " + subWorkFlowDTO.getTierName(), e);
        }

        if (StringUtils.isBlank(priceId)) {
            throw new WorkflowException("No Stripe price ID found for tier '"
                    + subWorkFlowDTO.getTierName()
                    + "' on API product: " + apiProduct.getId().getName());
        }

        // Always create a new Checkout Session — no silent payment method reuse
        Session checkoutSession = createCheckoutSession(
                subscriber, subWorkFlowDTO, apiProduct.getUuid(), priceId, requestOptions);

        try {
            stripeMonetizationDAO.saveCheckoutSession(
                    checkoutSession.getId(),
                    subWorkFlowDTO.getWorkflowReference(),
                    subscriber.getId(),
                    subWorkFlowDTO.getTenantId(),
                    apiProduct.getUuid(),
                    checkoutSession.getUrl());
        } catch (StripeMonetizationException e) {
            throw new WorkflowException("Failed to persist checkout session for workflow: "
                    + subWorkFlowDTO.getWorkflowReference(), e);
        }

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
            log.debug("Stripe Checkout Session created: subscriber=" + subscriber.getName()
                    + " APIProduct=" + apiProduct.getId().getName()
                    + " workflowRef=" + subWorkFlowDTO.getWorkflowReference());
        }

        HttpWorkflowResponse httpWorkflowResponse = new HttpWorkflowResponse();
        httpWorkflowResponse.setRedirectUrl(checkoutSession.getUrl());
        return httpWorkflowResponse;
    }

    // =========================================================================
    // complete()
    // =========================================================================

    /**
     * Completes the subscription creation workflow and activates the APIM subscription.
     *
     * <p>Two completion paths are supported:
     * <ul>
     *   <li><b>Checkout path</b> — triggered when {@code workflowDTO.attributes} contains
     *       {@link StripeMonetizationConstants#CHECKOUT_SESSION_ID_ATTRIBUTE}. Set by the
     *       webhook handler or browser-redirect complete-session servlet before calling this
     *       method. A DB-level idempotency guard (PENDING → IN_PROGRESS) ensures only one
     *       concurrent path processes the session; the other skips if COMPLETED or aborts
     *       if still IN_PROGRESS.</li>
     *   <li><b>Direct path</b> — called by {@link #execute} for non-monetized subscriptions
     *       with status already set to APPROVED.</li>
     * </ul>
     *
     * <p>The caller is responsible for setting {@code workflowDTO.status = APPROVED} before
     * invoking this method on the checkout path.
     *
     * @param workflowDTO the workflow context
     * @return a {@link GeneralWorkflowResponse}
     * @throws WorkflowException if workflow completion fails
     */
    @Override
    public WorkflowResponse complete(WorkflowDTO workflowDTO) throws WorkflowException {

        // ── Checkout-triggered path ──────────────────────────────────────────
        String checkoutSessionId = workflowDTO.getAttributes().get(
                StripeMonetizationConstants.CHECKOUT_SESSION_ID_ATTRIBUTE);

        if (!StringUtils.isBlank(checkoutSessionId)) {

            // Atomically claim: PENDING → IN_PROGRESS.
            // Both webhook and browser-redirect call complete() concurrently.
            // Only the winner (rowsAffected == 1) proceeds; the loser sees false.
            boolean claimed;
            try {
                claimed = stripeMonetizationDAO.claimCheckoutSession(checkoutSessionId);
            } catch (StripeMonetizationException e) {
                throw new WorkflowException(
                        "Failed to claim checkout session: " + checkoutSessionId, e);
            }

            if (claimed) {
                try {
                    completeStripeCheckoutSubscription(workflowDTO, checkoutSessionId);
                } catch (WorkflowException e) {
                    // Reset claim so the other path can retry
                    try {
                        stripeMonetizationDAO.resetCheckoutSessionClaim(checkoutSessionId);
                    } catch (StripeMonetizationException resetEx) {
                        log.error("Failed to reset claim for session " + checkoutSessionId
                                + " after completion error", resetEx);
                    }
                    throw e;
                }
            } else {
                // Another path already claimed this session.
                // Allow fall-through to updateSubscriptionStatus only when COMPLETED.
                // If still IN_PROGRESS abort — we must not prematurely activate.
                Map<String, String> sessionRow;
                try {
                    sessionRow = stripeMonetizationDAO.getCheckoutSession(checkoutSessionId);
                } catch (StripeMonetizationException e) {
                    throw new WorkflowException(
                            "Failed to read session status for: " + checkoutSessionId, e);
                }
                String currentStatus = sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_STATUS);
                if (!StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_COMPLETED.equals(currentStatus)) {
                    throw new WorkflowException("Checkout session " + checkoutSessionId
                            + " is still being processed by another path (status=" + currentStatus
                            + ") — aborting to avoid premature subscription activation");
                }
                log.info("Checkout session " + checkoutSessionId
                        + " already completed by another path — skipping Stripe work");
            }
        }

        // ── Standard workflow DB update ──────────────────────────────────────
        workflowDTO.setUpdatedTime(System.currentTimeMillis());
        super.complete(workflowDTO);   // persists WF_STATUS in AM_WORKFLOWS

        try {
            if (WorkflowStatus.APPROVED.equals(workflowDTO.getStatus())) {
                ApiMgtDAO.getInstance().updateSubscriptionStatus(
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
    // Checkout completion
    // =========================================================================

    /**
     * Performs all persistence work to complete a subscription initiated via Stripe Checkout
     * in {@code subscription} mode. Invoked only when {@link #complete} holds the
     * idempotency claim on the session.
     *
     * <p>Steps:
     * <ol>
     *   <li>Load the checkout session from the local DB to obtain the trusted API UUID.</li>
     *   <li>Retrieve the Stripe Checkout Session from the connected account.</li>
     *   <li>Validate {@code payment_status == "paid"}.</li>
     *   <li>Cross-reference the session metadata API UUID against the local DB record.</li>
     *   <li>Insert a shared customer row for the checkout-created connected account customer
     *       (enables billing portal and deletion workflow lookups).</li>
     *   <li>Insert a subscription row in {@code AM_MONETIZATION_SUBSCRIPTIONS}.</li>
     *   <li>Mark the checkout session {@code COMPLETED}.</li>
     * </ol>
     *
     * <p><strong>Prerequisite:</strong> The FK constraint on
     * {@code AM_MONETIZATION_SHARED_CUSTOMERS.PARENT_CUSTOMER_ID} must be dropped before
     * this method is used. See the schema migration in
     * {@code docs/per-subscription-checkout-design.md}.
     *
     * @param workflowDTO       workflow context; must carry tenantId and tenantDomain
     * @param checkoutSessionId Stripe Checkout Session ID ({@code cs_xxx})
     * @throws WorkflowException wrapping any Stripe, APIM, or DB error
     */
    private void completeStripeCheckoutSubscription(WorkflowDTO workflowDTO, String checkoutSessionId)
            throws WorkflowException {

        try {
            // 1. Set the platform Stripe API key
            Stripe.apiKey = getPlatformAccountKey(workflowDTO.getTenantId());

            // 2. Load session from the local DB — authoritative source for the API UUID.
            //    Never rely solely on Stripe session metadata for security-sensitive lookups.
            Map<String, String> sessionRow = stripeMonetizationDAO.getCheckoutSession(checkoutSessionId);
            if (sessionRow == null || sessionRow.isEmpty()) {
                throw new WorkflowException(
                        "Checkout session not found in local DB: " + checkoutSessionId);
            }
            String trustedApiUuid = sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_API_UUID);
            if (StringUtils.isBlank(trustedApiUuid)) {
                throw new WorkflowException(
                        "API UUID missing from DB record for session: " + checkoutSessionId);
            }

            // 3. Resolve the connected account key from the trusted API UUID
            String connectedAccountKey = getConnectedAccountKey(
                    trustedApiUuid, workflowDTO.getTenantDomain());
            RequestOptions requestOptions = RequestOptions.builder()
                    .setStripeAccount(connectedAccountKey).build();

            // 4. Retrieve the Stripe Checkout Session from the connected account
            Session session = Session.retrieve(checkoutSessionId, requestOptions);

            // 5. Validate that Stripe collected payment before activating the subscription
            if (!"paid".equals(session.getPaymentStatus())) {
                throw new WorkflowException(
                        "Payment not confirmed for session: " + checkoutSessionId
                        + " (payment_status=" + session.getPaymentStatus() + ")");
            }

            // 6. Cross-reference: metadata API UUID must match the trusted DB record
            Map<String, String> meta = session.getMetadata();
            String metaApiUuid = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_UUID);
            if (!trustedApiUuid.equals(metaApiUuid)) {
                throw new WorkflowException(
                        "Session metadata API UUID '" + metaApiUuid
                        + "' does not match DB record '" + trustedApiUuid
                        + "' for session: " + checkoutSessionId);
            }

            // 7. Extract the Stripe-created subscription and customer IDs
            String stripeSubId      = session.getSubscription();
            String stripeCustomerId = session.getCustomer();
            if (StringUtils.isBlank(stripeSubId) || StringUtils.isBlank(stripeCustomerId)) {
                throw new WorkflowException(
                        "Checkout session is missing subscription or customer ID: "
                        + checkoutSessionId);
            }

            // 8. Read subscription context from session metadata
            int    applicationId = Integer.parseInt(
                    meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_APPLICATION_ID));
            String apiProvider   = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_PROVIDER);
            String apiName       = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_NAME);
            String apiVersion    = meta.get(StripeMonetizationConstants.CHECKOUT_METADATA_API_VERSION);

            // 9. Insert a shared customer record for the checkout-created connected account customer.
            //    Required so the Stripe Customer Portal and the deletion workflow can look up
            //    the Stripe customer and subscription for this application.
            //
            //    PARENT_CUSTOMER_ID is set to 0 because this deployment does not use the
            //    AM_MONETIZATION_PLATFORM_CUSTOMERS table. The FK constraint must be dropped
            //    via the schema migration before this insert will succeed.
            MonetizationSharedCustomer sharedCustomer = new MonetizationSharedCustomer();
            sharedCustomer.setApplicationId(applicationId);
            sharedCustomer.setApiProvider(apiProvider);
            sharedCustomer.setTenantId(workflowDTO.getTenantId());
            sharedCustomer.setSharedCustomerId(stripeCustomerId);
            sharedCustomer.setParentCustomerId(0);
            int sharedCustomerDbId = stripeMonetizationDAO.addBESharedCustomer(sharedCustomer);

            // 10. Persist the Stripe subscription to the APIM monetization DB
            APIIdentifier identifier = new APIIdentifier(apiProvider, apiName, apiVersion);
            stripeMonetizationDAO.addBESubscription(
                    identifier, applicationId, workflowDTO.getTenantId(),
                    sharedCustomerDbId, stripeSubId, trustedApiUuid);

            // 11. Mark the checkout session COMPLETED — best-effort after subscription is persisted
            try {
                stripeMonetizationDAO.updateCheckoutSessionStatus(
                        checkoutSessionId,
                        StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_COMPLETED);
            } catch (StripeMonetizationException e) {
                log.error("Failed to mark checkout session COMPLETED (subscription already persisted): "
                        + checkoutSessionId, e);
            }

            log.info("Stripe Checkout subscription completed: stripeSubscription=" + stripeSubId
                    + " session=" + checkoutSessionId);

        } catch (StripeException e) {
            throw new WorkflowException(
                    "Stripe API error during checkout completion for session: "
                    + checkoutSessionId, e);
        } catch (StripeMonetizationException e) {
            throw new WorkflowException(
                    "Monetization DB error during checkout completion for session: "
                    + checkoutSessionId, e);
        }
    }

    // =========================================================================
    // Checkout Session creation
    // =========================================================================

    /**
     * Creates a Stripe Checkout Session in {@code subscription} mode on the API provider's
     * connected Stripe account.
     *
     * <p>The session collects payment and immediately creates the Stripe subscription when
     * the user completes checkout. The connected account is targeted via
     * {@code requestOptions} ({@code Stripe-Account} header) so the customer, subscription,
     * and invoices are all scoped to the provider's account — consistent with how the
     * billing portal and deletion workflow locate them.
     *
     * <p>No {@code setCurrency()} call is made; currency is determined by the price object
     * on the connected account.
     *
     * @param subscriber     the APIM subscriber
     * @param subWorkFlowDTO subscription workflow DTO
     * @param apiUuid        UUID of the API or API product
     * @param priceId        Stripe price ID for the selected tier
     * @param requestOptions connected account credentials
     * @return the created Stripe Checkout Session
     * @throws WorkflowException if the session cannot be created or URLs are not configured
     */
    private Session createCheckoutSession(Subscriber subscriber,
            SubscriptionWorkflowDTO subWorkFlowDTO,
            String apiUuid, String priceId,
            RequestOptions requestOptions) throws WorkflowException {

        if (StringUtils.isBlank(checkoutSuccessUrl) || StringUtils.isBlank(checkoutCancelUrl)) {
            throw new WorkflowException(
                    "checkoutSuccessUrl and checkoutCancelUrl must be configured in "
                    + "workflow-extensions.xml for StripeSubscriptionCreationWorkflowExecutor");
        }

        try {
            // Build success URL: {base}/{applicationUUID}/subscriptions?session_id={CHECKOUT_SESSION_ID}
            String applicationUUID = getApplicationUUID(subWorkFlowDTO.getApplicationId());
            String appPath = (applicationUUID != null)
                    ? applicationUUID
                    : String.valueOf(subWorkFlowDTO.getApplicationId());
            String successUrl = checkoutSuccessUrl
                    + "/" + appPath
                    + "/subscriptions?session_id={CHECKOUT_SESSION_ID}";

            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(checkoutCancelUrl)
                    // Subscription line item — Stripe creates the subscription on checkout completion
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setPrice(priceId)
                                    .setQuantity(1L)
                                    .build())
                    // Core identifiers used to resume the APIM workflow on completion
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_WORKFLOW_REF,
                            subWorkFlowDTO.getWorkflowReference())
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_SUBSCRIBER_ID,
                            String.valueOf(subscriber.getId()))
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_TENANT_ID,
                            String.valueOf(subWorkFlowDTO.getTenantId()))
                    .putMetadata(StripeMonetizationConstants.CHECKOUT_METADATA_API_UUID, apiUuid)
                    // Subscription context — avoids extra DB lookups in completeStripeCheckoutSubscription
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

            // Pre-fill subscriber email on the Stripe-hosted form when available
            if (!StringUtils.isEmpty(subscriber.getEmail())) {
                paramsBuilder.setCustomerEmail(subscriber.getEmail());
            }

            // Create on the connected account — requestOptions carries the Stripe-Account header
            return Session.create(paramsBuilder.build(), requestOptions);

        } catch (StripeException e) {
            throw new WorkflowException(
                    "Failed to create Stripe Checkout Session for subscriber: " + subscriber.getName()
                    + " workflowRef: " + subWorkFlowDTO.getWorkflowReference(), e);
        }
    }

    // =========================================================================
    // Configuration and lookup helpers
    // =========================================================================

    /**
     * Returns the Stripe platform account secret key for the given tenant, read from the
     * tenant configuration JSON stored in the APIM registry.
     *
     * @param tenantId numeric tenant ID
     * @return Stripe platform secret key (sk_live_xxx or sk_test_xxx)
     * @throws WorkflowException if the key is blank or the config cannot be read
     */
    private String getPlatformAccountKey(int tenantId) throws WorkflowException {
        String tenantDomain = APIUtil.getTenantDomainFromTenantId(tenantId);
        try {
            JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
            if (tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
                JSONObject monetizationInfo = (JSONObject) tenantConfig
                        .get(StripeMonetizationConstants.MONETIZATION_INFO);
                if (monetizationInfo.containsKey(
                        StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)) {
                    String key = monetizationInfo
                            .get(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)
                            .toString();
                    if (StringUtils.isBlank(key)) {
                        throw new WorkflowException(
                                "Stripe platform account key is empty for tenant: " + tenantDomain);
                    }
                    return key;
                }
            }
        } catch (APIManagementException e) {
            throw new WorkflowException(
                    "Failed to read tenant configuration for: " + tenantDomain, e);
        }
        throw new WorkflowException(
                "Stripe platform account key (BillingEnginePlatformAccountKey) not configured "
                + "for tenant: " + tenantDomain);
    }

    /**
     * Returns the Stripe connected account ID from the API's monetization properties.
     * Initialises {@link #apiPersistenceInstance} lazily on first call.
     *
     * <p><strong>Note:</strong> Uses {@code getPublisherAPI()} — applicable to regular APIs.
     * For API product UUIDs passed from {@link #completeStripeCheckoutSubscription}, verify
     * that the persistence layer resolves product UUIDs correctly in the target environment.
     * See docs/per-subscription-checkout-design.md §7.1.
     *
     * @param apiUuid      UUID of the API
     * @param tenantDomain tenant domain
     * @return Stripe connected account ID (e.g. {@code acct_xxx})
     * @throws WorkflowException if the key is missing or the API cannot be loaded
     */
    private String getConnectedAccountKey(String apiUuid, String tenantDomain)
            throws WorkflowException {

        if (apiPersistenceInstance == null) {
            Properties props = new Properties();
            props.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                    APIUtil.isAllowDisplayAPIsWithMultipleStatus());
            props.put(APIConstants.ALLOW_MULTIPLE_VERSIONS,
                    APIUtil.isAllowDisplayMultipleVersions());
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
     * Looks up the application UUID from its numeric DB ID in {@code AM_APPLICATION}.
     * The DevPortal uses UUIDs in its routes
     * ({@code /devportal/applications/{UUID}/subscriptions}), not integer IDs.
     *
     * @param applicationId numeric application DB ID
     * @return the application UUID, or {@code null} if the lookup fails
     *         (the numeric ID is used as a fallback in the success URL)
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
}
