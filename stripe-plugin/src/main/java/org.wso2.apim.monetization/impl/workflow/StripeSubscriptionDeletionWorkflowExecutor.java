/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.apim.monetization.impl.workflow;

import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
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
import org.wso2.apim.monetization.impl.model.MonetizedSubscription;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.SubscriptionWorkflowDTO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.workflow.GeneralWorkflowResponse;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * worrkflow executor for stripe based subscription delete action
 */
public class StripeSubscriptionDeletionWorkflowExecutor extends WorkflowExecutor {

    private static final Log log = LogFactory.getLog(StripeSubscriptionDeletionWorkflowExecutor.class);
    APIPersistence apiPersistenceInstance;

    @Override
    public String getWorkflowType() {
        return WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_DELETION;
    }

    @Override
    public List<WorkflowDTO> getWorkflowDetails(String workflowStatus) throws WorkflowException {
        // implemetation is not provided in this version
        return null;
    }

    /**
     * This method executes subscription deletion workflow and return workflow response back to the caller
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @return workflow response back to the caller
     * @throws WorkflowException Thrown when the workflow execution was not fully performed
     */
    @Override
    public WorkflowResponse execute(WorkflowDTO workflowDTO) throws WorkflowException {

        workflowDTO.setStatus(WorkflowStatus.APPROVED);
        complete(workflowDTO);
        return new GeneralWorkflowResponse();
    }

    /**
     * This method executes monetization related functions in the subscription deletion workflow
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @param api         API
     * @return workflow response back to the caller
     * @throws WorkflowException Thrown when the workflow execution was not fully performed
     */
    @Override
    public WorkflowResponse deleteMonetizedSubscription(WorkflowDTO workflowDTO, API api) throws WorkflowException {

        SubscriptionWorkflowDTO subWorkflowDTO;
        MonetizedSubscription monetizedSubscription;
        StripeMonetizationDAO stripeMonetizationDAO = new StripeMonetizationDAO();
        subWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
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
        //read the platform key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkflowDTO.getTenantId());
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
            // get the key of the connected account
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for : "
                        + api.getId().getApiName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to add,remove artifacts in connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try {
            //get the stripe subscription id
            monetizedSubscription = stripeMonetizationDAO
                    .getMonetizedSubscription(api.getUuid(), subWorkflowDTO.getApiName(),
                            subWorkflowDTO.getApplicationId(), subWorkflowDTO.getTenantDomain());
        } catch (StripeMonetizationException ex) {
            String errorMessage = "Could not retrieve monetized subscription info for : "
                    + subWorkflowDTO.getApplicationName() + " by Application : " + subWorkflowDTO.getApplicationName();
            throw new WorkflowException(errorMessage, ex);
        }
        if (monetizedSubscription.getSubscriptionId() != null) {
            try {
                Subscription subscription = Subscription.retrieve(monetizedSubscription.getSubscriptionId(),
                        requestOptions);
                Map<String, Object> params = new HashMap<>();
                //canceled subscription will be invoiced immediately
                params.put(StripeMonetizationConstants.INVOICE_NOW, true);
                subscription = subscription.cancel(params, requestOptions);
                if (StringUtils.equals(subscription.getStatus(), StripeMonetizationConstants.CANCELED)) {
                    stripeMonetizationDAO.removeMonetizedSubscription(monetizedSubscription.getId());
                }
                if (log.isDebugEnabled()) {
                    String msg = "Monetized subscriprion for : " + subWorkflowDTO.getApiName()
                            + " by Application : " + subWorkflowDTO.getApplicationName() + " is removed successfully ";
                    log.debug(msg);
                }
            } catch (StripeException ex) {
                String errorMessage = "Failed to remove subcription in billing engine for : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            } catch (StripeMonetizationException ex) {
                String errorMessage = "Failed to remove monetization subcription info from DB of : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            }
        }
        return execute(workflowDTO);
    }

    @Override
    public WorkflowResponse deleteMonetizedSubscription(WorkflowDTO workflowDTO, APIProduct apiProduct)
            throws WorkflowException {

        SubscriptionWorkflowDTO subWorkflowDTO;
        MonetizedSubscription monetizedSubscription;
        StripeMonetizationDAO stripeMonetizationDAO = new StripeMonetizationDAO();
        subWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        //read the platform key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkflowDTO.getTenantId());
        String connectedAccountKey = StringUtils.EMPTY;
        Map<String, String> monetizationProperties = new Gson().fromJson(apiProduct.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            // get the key of the connected account
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for : " + apiProduct.getId().getName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to add,remove artifacts in connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try {
            //get the stripe subscription id
            monetizedSubscription = stripeMonetizationDAO
                    .getMonetizedSubscription(apiProduct.getUuid(), subWorkflowDTO.getApiName(),
                            subWorkflowDTO.getApplicationId(), subWorkflowDTO.getTenantDomain());
        } catch (StripeMonetizationException ex) {
            String errorMessage = "Could not retrieve monetized subscription info for : "
                    + subWorkflowDTO.getApplicationName() + " by application : " + subWorkflowDTO.getApplicationName();
            throw new WorkflowException(errorMessage, ex);
        }
        if (monetizedSubscription.getSubscriptionId() != null) {
            try {
                Subscription subscription = Subscription.retrieve(monetizedSubscription.getSubscriptionId(),
                        requestOptions);
                Map<String, Object> params = new HashMap<>();
                //canceled subscription will be invoiced immediately
                params.put(StripeMonetizationConstants.INVOICE_NOW, true);
                subscription = subscription.cancel(params, requestOptions);
                if (StringUtils.equals(subscription.getStatus(), StripeMonetizationConstants.CANCELED)) {
                    stripeMonetizationDAO.removeMonetizedSubscription(monetizedSubscription.getId());
                }
                if (log.isDebugEnabled()) {
                    String msg = "Monetized subscriprion for : " + subWorkflowDTO.getApiName()
                            + " by application : " + subWorkflowDTO.getApplicationName() + " is removed successfully ";
                    log.debug(msg);
                }
            } catch (StripeException ex) {
                String errorMessage = "Failed to remove subcription in billing engine for : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            } catch (StripeMonetizationException ex) {
                String errorMessage = "Failed to remove monetization subcription info from DB of : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            }
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
     * This method completes subscription creation workflow and return workflow response back to the caller
     *
     * @param workflowDTO The WorkflowDTO which contains workflow contextual information related to the workflow
     * @return workflow response back to the caller
     * @throws WorkflowException
     */
    @Override
    public WorkflowResponse complete(WorkflowDTO workflowDTO) throws WorkflowException {

        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        SubscriptionWorkflowDTO subWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        String errorMsg = null;

        try {
            APIIdentifier identifier = new APIIdentifier(subWorkflowDTO.getApiProvider(),
                    subWorkflowDTO.getApiName(), subWorkflowDTO.getApiVersion());

            apiMgtDAO.removeSubscription(identifier, ((SubscriptionWorkflowDTO) workflowDTO).getApplicationId());
        } catch (APIManagementException e) {
            errorMsg = "Could not complete subscription deletion workflow for api: " + subWorkflowDTO.getApiName();
            throw new WorkflowException(errorMsg, e);
        }
        return new GeneralWorkflowResponse();
    }
}
