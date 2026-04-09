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

package org.wso2.apim.monetization.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.TermsAggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.Plan;
import com.stripe.model.Product;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.UsageRecord;
import com.stripe.net.RequestOptions;
import feign.Feign;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.wso2.apim.monetization.impl.model.GraphQLClient;
import org.wso2.apim.monetization.impl.model.GraphqlQueryModel;
import org.wso2.apim.monetization.impl.model.MonetizedSubscription;
import org.wso2.apim.monetization.impl.model.QueyAPIAccessTokenInterceptor;
import org.wso2.apim.monetization.impl.model.graphQLResponseClient;
import org.wso2.carbon.apimgt.api.APIAdmin;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.MonetizationException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIInfo;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.APIProductIdentifier;
import org.wso2.carbon.apimgt.api.model.Application;
import org.wso2.carbon.apimgt.api.model.Monetization;
import org.wso2.carbon.apimgt.api.model.MonetizationUsagePublishInfo;
import org.wso2.carbon.apimgt.api.model.SubscribedAPI;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import org.wso2.carbon.apimgt.impl.APIAdminImpl;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.internal.MonetizationDataHolder;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APINameComparator;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.persistence.APIPersistence;
import org.wso2.carbon.apimgt.persistence.PersistenceManager;
import org.wso2.carbon.apimgt.persistence.dto.Organization;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPI;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPIInfo;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPISearchResult;
import org.wso2.carbon.apimgt.persistence.dto.UserContext;
import org.wso2.carbon.apimgt.persistence.exceptions.APIPersistenceException;
import org.wso2.carbon.apimgt.persistence.mapper.APIMapper;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.API_UUID;
import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.APPLICATION_ID_COLUMN;
import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.DEFAULT_ELK_ANALYTICS_INDEX;
import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.ELK_API_ID_COL;
import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.ELK_APPLICATION_ID_COLUMN;
import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.ELK_TENANT_DOMAIN;
import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.REQUEST_TIMESTAMP_COLUMN;
import static org.wso2.apim.monetization.impl.StripeMonetizationConstants.TENANT_DOMAIN_COL;

/**
 * This class is used to implement stripe based monetization
 */
public class StripeMonetizationImpl implements Monetization {

    private static final Log log = LogFactory.getLog(StripeMonetizationImpl.class);
    private StripeMonetizationDAO stripeMonetizationDAO = StripeMonetizationDAO.getInstance();
    private static APIManagerConfiguration config = null;
    private ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
    APIPersistence apiPersistenceInstance;
    boolean useNewQueryAPI = true;

    /**
     * Create billing plan for a policy
     *
     * @param subscriptionPolicy subscription policy
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean createBillingPlan(SubscriptionPolicy subscriptionPolicy) throws MonetizationException {

        try {
            //read tenant conf and get platform account key
            Stripe.apiKey = getStripePlatformAccountKey(subscriptionPolicy.getTenantDomain());
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get Stripe platform account key for tenant :  " +
                    subscriptionPolicy.getTenantDomain();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        Map<String, Object> productParams = new HashMap<String, Object>();
        productParams.put(APIConstants.POLICY_NAME_ELEM, subscriptionPolicy.getTenantDomain() +
                "-" + subscriptionPolicy.getPolicyName());
        productParams.put(APIConstants.TYPE, StripeMonetizationConstants.SERVICE_TYPE);
        Timestamp timestamp = new Timestamp(new Date().getTime());
        String productCreationIdempotencyKey = subscriptionPolicy.getTenantDomain() + timestamp.toString();
        RequestOptions productRequestOptions = RequestOptions.builder().
                setIdempotencyKey(productCreationIdempotencyKey).build();
        try {
            Product product = Product.create(productParams, productRequestOptions);
            String productId = product.getId();
            if (StringUtils.isBlank(productId)) {
                String errorMessage = "Failed to create stripe product for tenant : " +
                        subscriptionPolicy.getTenantDomain();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
            Map<String, Object> planParams = new HashMap<String, Object>();
            String currencyType = subscriptionPolicy.getMonetizationPlanProperties().
                    get(APIConstants.Monetization.CURRENCY).toLowerCase();
            planParams.put(StripeMonetizationConstants.CURRENCY, currencyType);
            planParams.put(StripeMonetizationConstants.PRODUCT, productId);
            planParams.put(StripeMonetizationConstants.PRODUCT_NICKNAME, subscriptionPolicy.getPolicyName());
            planParams.put(StripeMonetizationConstants.INTERVAL,
                    subscriptionPolicy.getMonetizationPlanProperties().get(APIConstants.Monetization.BILLING_CYCLE));
            if (APIConstants.Monetization.FIXED_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.FIXED_PRICE));
                //need to multiply the input for "amount" by 100 for stripe (because it divides the value by 100)
                //also, since stripe supports only integers, convert the amount to an int before creating the plan
                planParams.put(StripeMonetizationConstants.AMOUNT, (int) (amount * 100));
                planParams.put(StripeMonetizationConstants.USAGE_TYPE, StripeMonetizationConstants.LICENSED_USAGE);
            }
            if (StripeMonetizationConstants.DYNAMIC_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.PRICE_PER_REQUEST));
                //need to multiply the input for "amount" by 100 for stripe (because it divides the value by 100)
                //also, since stripe supports only integers, convert the amount to an int before creating the plan
                planParams.put(StripeMonetizationConstants.AMOUNT, (int) (amount * 100));
                planParams.put(StripeMonetizationConstants.USAGE_TYPE, StripeMonetizationConstants.METERED_USAGE);
            }
            RequestOptions planRequestOptions = RequestOptions.builder().
                    setIdempotencyKey(subscriptionPolicy.getUUID()).build();
            Plan plan = Plan.create(planParams, planRequestOptions);
            String createdPlanId = plan.getId();
            //put the newly created stripe plans and tiers into a map (to add data to the database)
            if (StringUtils.isBlank(createdPlanId)) {
                String errorMessage = "Failed to create plan for tier : " + subscriptionPolicy.getPolicyName() +
                        " in " + subscriptionPolicy.getTenantDomain();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
            //add database record
            stripeMonetizationDAO.addMonetizationPlanData(subscriptionPolicy, productId, createdPlanId);
            return true;
        } catch (StripeException e) {
            String errorMessage = "Failed to create monetization plan for : " + subscriptionPolicy.getPolicyName() +
                    " in stripe.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to create monetization plan for : " + subscriptionPolicy.getPolicyName() +
                    " in the database.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
    }

    /**
     * Update billing plan of a policy
     *
     * @param subscriptionPolicy subscription policy
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean updateBillingPlan(SubscriptionPolicy subscriptionPolicy) throws MonetizationException {

        Map<String, String> planData = null;
        try {
            planData = stripeMonetizationDAO.getPlanData(subscriptionPolicy);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get stripe plan data for policy : " + subscriptionPolicy.getPolicyName() +
                    " when updating billing plan.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        String oldProductId = null;
        String oldPlanId = null;
        String newProductId = null;
        String updatedPlanId = null;
        try {
            //read tenant-conf.json and get platform account key
            Stripe.apiKey = getStripePlatformAccountKey(subscriptionPolicy.getTenantDomain());
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get Stripe platform account key for tenant :  " +
                    subscriptionPolicy.getTenantDomain() + " when updating billing plan.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        if (MapUtils.isNotEmpty(planData)) {
            //product and plan exists for the older plan, so get those values and proceed
            oldProductId = planData.get(StripeMonetizationConstants.PRODUCT_ID);
            oldPlanId = planData.get(StripeMonetizationConstants.PLAN_ID);
        } else {
            //this means updating the monetization plan of tier from a free to commercial.
            //since there is no plan (for old - free tier), we should create a product and plan for the updated tier
            Map<String, Object> productParams = new HashMap<String, Object>();
            productParams.put(APIConstants.POLICY_NAME_ELEM,
                    subscriptionPolicy.getTenantDomain() + "-" + subscriptionPolicy.getPolicyName());
            productParams.put(APIConstants.TYPE, StripeMonetizationConstants.SERVICE_TYPE);
            Timestamp timestamp = new Timestamp(new Date().getTime());
            String productCreationIdempotencyKey = subscriptionPolicy.getTenantDomain() + timestamp.toString();
            RequestOptions productRequestOptions = RequestOptions.builder().
                    setIdempotencyKey(productCreationIdempotencyKey).build();
            try {
                Product product = Product.create(productParams, productRequestOptions);
                newProductId = product.getId();
                if (StringUtils.isBlank(newProductId)) {
                    String errorMessage = "No stripe product was created for tenant (when updating policy) : " +
                            subscriptionPolicy.getTenantDomain();
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
            } catch (StripeException e) {
                String errorMessage = "Failed to create stripe product for tenant (when updating policy) : " +
                        subscriptionPolicy.getTenantDomain();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            }
        }
        //delete old plan if exists
        if (StringUtils.isNotBlank(oldPlanId)) {
            try {
                Plan oldPlan = Plan.retrieve(oldPlanId);
                if (oldPlan != null) {
                    oldPlan.delete();
                }
            } catch (StripeException e) {
                String errorMessage = "Failed to delete old plan for policy : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                log.warn(errorMessage + " due to " + e);
            }
        }
        //if updated to a commercial plan, create new plan in billing engine and update DB record
        if (APIConstants.COMMERCIAL_TIER_PLAN.equalsIgnoreCase(subscriptionPolicy.getBillingPlan())) {
            Map<String, Object> planParams = new HashMap<String, Object>();
            String currencyType = subscriptionPolicy.getMonetizationPlanProperties().
                    get(APIConstants.Monetization.CURRENCY).toLowerCase();
            planParams.put(StripeMonetizationConstants.CURRENCY, currencyType);
            if (StringUtils.isNotBlank(oldProductId)) {
                planParams.put(StripeMonetizationConstants.PRODUCT, oldProductId);
            }
            if (StringUtils.isNotBlank(newProductId)) {
                planParams.put(StripeMonetizationConstants.PRODUCT, newProductId);
            }
            planParams.put(StripeMonetizationConstants.PRODUCT_NICKNAME, subscriptionPolicy.getPolicyName());
            planParams.put(StripeMonetizationConstants.INTERVAL, subscriptionPolicy.getMonetizationPlanProperties().
                    get(APIConstants.Monetization.BILLING_CYCLE));

            if (APIConstants.Monetization.FIXED_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.FIXED_PRICE));
                //need to multiply the input for "amount" by 100 for stripe (because it divides the value by 100)
                //also, since stripe supports only integers, convert the amount to an int before creating the plan
                planParams.put(StripeMonetizationConstants.AMOUNT, (int) (amount * 100));
                planParams.put(StripeMonetizationConstants.USAGE_TYPE, StripeMonetizationConstants.LICENSED_USAGE);
            }
            if (StripeMonetizationConstants.DYNAMIC_RATE.equalsIgnoreCase(subscriptionPolicy.getMonetizationPlan())) {
                float amount = Float.parseFloat(subscriptionPolicy.getMonetizationPlanProperties().
                        get(APIConstants.Monetization.PRICE_PER_REQUEST));
                //need to multiply the input for "amount" by 100 for stripe (because it divides the value by 100)
                //also, since stripe supports only integers, convert the amount to an int before creating the plan
                planParams.put(StripeMonetizationConstants.AMOUNT, (int) (amount * 100));
                planParams.put(StripeMonetizationConstants.USAGE_TYPE, StripeMonetizationConstants.METERED_USAGE);
            }
            Plan updatedPlan = null;
            try {
                updatedPlan = Plan.create(planParams);
            } catch (StripeException e) {
                String errorMessage = "Failed to create stripe plan for tier : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            }
            if (updatedPlan != null) {
                updatedPlanId = updatedPlan.getId();
            } else {
                String errorMessage = "Failed to create plan for policy update : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
            if (StringUtils.isBlank(updatedPlanId)) {
                String errorMessage = "Failed to update stripe plan for tier : " + subscriptionPolicy.getPolicyName() +
                        " in " + subscriptionPolicy.getTenantDomain();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
        } else if (APIConstants.BILLING_PLAN_FREE.equalsIgnoreCase(subscriptionPolicy.getBillingPlan())) {
            try {
                //If updated to a free plan (from a commercial plan), no need to create any plan in the billing engine
                //hence delete the DB record
                stripeMonetizationDAO.deleteMonetizationPlanData(subscriptionPolicy);
                //Remove old artifacts in the billing engine (if any)
                if (StringUtils.isNotBlank(oldProductId)) {
                    Product.retrieve(oldProductId).delete();
                }
            } catch (StripeException e) {
                String errorMessage = "Failed to delete old stripe product for : " + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            } catch (StripeMonetizationException e) {
                String errorMessage = "Failed to delete monetization plan data from database for : " +
                        subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            }
        }
        try {
            if (StringUtils.isNotBlank(oldProductId)) {
                //update DB record
                stripeMonetizationDAO.updateMonetizationPlanData(subscriptionPolicy, oldProductId, updatedPlanId);
            }
            if (StringUtils.isNotBlank(newProductId)) {
                //create new DB record
                stripeMonetizationDAO.addMonetizationPlanData(subscriptionPolicy, newProductId, updatedPlanId);
            }
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to update monetization plan data in database for : " +
                    subscriptionPolicy.getPolicyName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        return true;
    }

    /**
     * Delete a billing plan of a policy
     *
     * @param subscriptionPolicy subscription policy
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean deleteBillingPlan(SubscriptionPolicy subscriptionPolicy) throws MonetizationException {

        //get old plan (if any) in the billing engine and delete
        Map<String, String> planData = null;
        try {
            planData = stripeMonetizationDAO.getPlanData(subscriptionPolicy);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get stripe plan data for policy : " + subscriptionPolicy.getPolicyName() +
                    " when deleting billing plan.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        if (MapUtils.isEmpty(planData)) {
            log.debug("No billing plan found for : " + subscriptionPolicy.getPolicyName());
            return true;
        }
        String productId = planData.get(StripeMonetizationConstants.PRODUCT_ID);
        String planId = planData.get(StripeMonetizationConstants.PLAN_ID);
        try {
            //read tenant-conf.json and get platform account key
            Stripe.apiKey = getStripePlatformAccountKey(subscriptionPolicy.getTenantDomain());
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get Stripe platform account key for tenant :  " +
                    subscriptionPolicy.getTenantDomain() + " when deleting billing plan.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        if (StringUtils.isNotBlank(planId)) {
            try {
                Plan.retrieve(planId).delete();
                Product.retrieve(productId).delete();
                stripeMonetizationDAO.deleteMonetizationPlanData(subscriptionPolicy);
            } catch (StripeException e) {
                String errorMessage = "Failed to delete billing plan resources of : "
                        + subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            } catch (StripeMonetizationException e) {
                String errorMessage = "Failed to delete billing plan data from database of policy : " +
                        subscriptionPolicy.getPolicyName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage, e);
            }
        }
        return true;
    }

    /**
     * Enable monetization for a API
     *
     * @param tenantDomain           tenant domain
     * @param api                    API
     * @param monetizationProperties monetization properties map
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean enableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws MonetizationException {

        String platformAccountKey = null;
        try {
            //read tenant conf and get platform account key
            platformAccountKey = getStripePlatformAccountKey(tenantDomain);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get Stripe platform account key for tenant :  " +
                    tenantDomain + " when enabling monetization for : " + api.getId().getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        String connectedAccountKey;
        //get api publisher's stripe key (i.e - connected account key) from monetization properties in request payload
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for : " + api.getId().getApiName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage);
        }
        String apiName = api.getId().getApiName();
        String apiVersion = api.getId().getVersion();
        String apiProvider = api.getId().getProviderName();
        try (Connection con = APIMgtDBUtil.getConnection()) {
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getUuid(), con);
            String billingProductIdForApi = getBillingProductIdForApi(apiId);
            //create billing engine product if it does not exist
            if (StringUtils.isEmpty(billingProductIdForApi)) {
                Stripe.apiKey = platformAccountKey;
                Map<String, Object> productParams = new HashMap<String, Object>();
                String stripeProductName = apiName + "-" + apiVersion + "-" + apiProvider;
                productParams.put(APIConstants.POLICY_NAME_ELEM, stripeProductName);
                productParams.put(APIConstants.TYPE, StripeMonetizationConstants.SERVICE_TYPE);
                RequestOptions productRequestOptions = RequestOptions.builder().setStripeAccount(
                        connectedAccountKey).build();
                try {
                    Product product = Product.create(productParams, productRequestOptions);
                    billingProductIdForApi = product.getId();
                } catch (StripeException e) {
                    String errorMessage = "Unable to create product in billing engine for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage, e);
                }
            }
            Map<String, String> tierPlanMap = new HashMap<String, String>();
            //scan for commercial tiers and add add plans in the billing engine if needed
            for (Tier currentTier : api.getAvailableTiers()) {
                if (APIConstants.COMMERCIAL_TIER_PLAN.equalsIgnoreCase(currentTier.getTierPlan())) {
                    String billingPlanId = getBillingPlanIdOfTier(apiId, currentTier.getName());
                    if (StringUtils.isBlank(billingPlanId)) {
                        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
                        String createdPlanId = createBillingPlanForCommercialTier(currentTier, tenantId,
                                platformAccountKey, connectedAccountKey, billingProductIdForApi);
                        if (StringUtils.isNotBlank(createdPlanId)) {
                            log.debug("Billing plan : " + createdPlanId + " successfully created for : " +
                                    currentTier.getName());
                            tierPlanMap.put(currentTier.getName(), createdPlanId);
                        } else {
                            log.debug("Failed to create billing plan for : " + currentTier.getName());
                        }
                    }
                }
            }
            //save data in the database - only if there is a stripe product and newly created plans
            if (StringUtils.isNotBlank(billingProductIdForApi) && MapUtils.isNotEmpty(tierPlanMap)) {
                stripeMonetizationDAO.addMonetizationData(apiId, billingProductIdForApi, tierPlanMap);
            } else {
                return false;
            }
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get ID from database for : " + apiName;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to create products and plans in stripe for : " + apiName;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new MonetizationException(errorMessage, e);
        }
        return true;
    }

    /**
     * Disable monetization for a API
     *
     * @param tenantDomain           tenant domain
     * @param api                    API
     * @param monetizationProperties monetization properties map
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean disableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws MonetizationException {

        String platformAccountKey = null;
        try {
            //read tenant conf and get platform account key
            platformAccountKey = getStripePlatformAccountKey(tenantDomain);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get Stripe platform account key for tenant :  " +
                    tenantDomain + " when disabling monetization for : " + api.getId().getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        String connectedAccountKey = StringUtils.EMPTY;
        //get api publisher's stripe key (i.e - connected account key) from monetization properties in request payload
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Billing engine connected account key was not found for : " +
                        api.getId().getApiName();
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty for tenant : " + tenantDomain;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage);
        }
        try (Connection con = APIMgtDBUtil.getConnection()) {
            String apiName = api.getId().getApiName();
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getUuid(), con);
            String billingProductIdForApi = getBillingProductIdForApi(apiId);
            //no product in the billing engine, so return
            if (StringUtils.isBlank(billingProductIdForApi)) {
                return false;
            }
            Map<String, String> tierToBillingEnginePlanMap = stripeMonetizationDAO.getTierToBillingEnginePlanMapping
                    (apiId, billingProductIdForApi);
            Stripe.apiKey = platformAccountKey;
            RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();

            for (Map.Entry<String, String> entry : tierToBillingEnginePlanMap.entrySet()) {
                String planId = entry.getValue();
                Plan plan = Plan.retrieve(planId, requestOptions);
                plan.delete(requestOptions);
                log.debug("Successfully deleted billing plan : " + planId + " of tier : " + entry.getKey());
            }
            //after deleting all the associated plans, then delete the product
            Product product = Product.retrieve(billingProductIdForApi, requestOptions);
            product.delete(requestOptions);
            log.debug("Successfully deleted billing product : " + billingProductIdForApi + " of : " + apiName);
            //after deleting plans and the product, clean the database records
            stripeMonetizationDAO.deleteMonetizationData(apiId);
            log.debug("Successfully deleted monetization database records for : " + apiName);
        } catch (StripeException e) {
            String errorMessage = "Failed to delete products and plans in the billing engine.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to fetch database records when disabling monetization for : " +
                    api.getId().getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get ID from database for : " + api.getId().getApiName() +
                    " when disabling monetization.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new MonetizationException(errorMessage, e);
        }
        return true;
    }

    /**
     * Get mapping of tiers and billing engine plans
     *
     * @param api API
     * @return tier to billing plan mapping
     * @throws MonetizationException if failed to get tier to billing plan mapping
     */
    public Map<String, String> getMonetizedPoliciesToPlanMapping(API api) throws MonetizationException {
        try (Connection con = APIMgtDBUtil.getConnection()) {
            String apiName = api.getId().getApiName();
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getUuid(), con);
            //get billing engine product ID for that API
            String billingProductIdForApi = getBillingProductIdForApi(apiId);
            if (StringUtils.isEmpty(billingProductIdForApi)) {
                log.info("No product was found in billing engine for  : " + apiName);
                return new HashMap<String, String>();
            }
            //get tier to billing engine plan mapping
            return stripeMonetizationDAO.getTierToBillingEnginePlanMapping(apiId, billingProductIdForApi);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get tier to billing engine plan mapping for : " + api.getId().getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get ID from database for : " + api.getId().getApiName() +
                    " when getting tier to billing engine plan mapping.";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new MonetizationException(e);
        }
    }

    /**
     * Publish monetization usage count
     *
     * @param lastPublishInfo Info about last published task
     * @return true if successful, false otherwise
     * @throws MonetizationException if the action failed
     */
    public boolean publishMonetizationUsageRecords(MonetizationUsagePublishInfo lastPublishInfo)
            throws MonetizationException {

        String apiUuid = null;
        String apiName = null;
        String apiVersion = null;
        String tenantDomain = null;
        String applicationName = null;
        String applicationOwner = null;
        int applicationId;
        String apiProvider = null;
        Long requestCount = 0L;
        Long currentTimestamp;
        int flag = 0;
        int counter = 0;
        APIAdmin apiAdmin = new APIAdminImpl();
        SubscriptionItem subscriptionItem = null;

        Date dateobj = new Date();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(StripeMonetizationConstants.TIME_FORMAT);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone(StripeMonetizationConstants.TIME_ZONE));
        String toDate = simpleDateFormat.format(dateobj);

        if (config == null) {
            // Retrieve the access token from api manager configurations.
            config = ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService().
                    getAPIManagerConfiguration();
        }
        //used for stripe recording
        currentTimestamp = getTimestamp(toDate);
        //The implementation will be improved to use offset date time to get the time zone based on user input
        String formattedToDate = toDate.concat(StripeMonetizationConstants.TIMEZONE_FORMAT);
        String fromDate = simpleDateFormat.format(
                new java.util.Date(lastPublishInfo.getLastPublishTime()));
        //The implementation will be improved to use offset date time to get the time zone based on user input
        String formattedFromDate = fromDate.concat(StripeMonetizationConstants.TIMEZONE_FORMAT);

        if (config.getFirstProperty("Analytics.Type") != null
                && !config.getFirstProperty("Analytics.Type").equals("")) {
            SearchResponse<Object> searchResponse = getUsageDataFromElasticsearch(fromDate, toDate);

            if (log.isDebugEnabled()) {
                log.debug("Collecting data from elasticsearch within the time range from" + fromDate + " to "
                        + toDate);
            }

            List<StringTermsBucket> apiIdBuckets = searchResponse.aggregations()
                    .get(API_UUID).sterms().buckets().array();
            if (apiIdBuckets.size() == 0) {
                try {
                    log.debug("No API Usage retrieved for the given period of time");
                    //last publish time will be updated as successfully since there was no usage retrieved.
                    lastPublishInfo.setLastPublishTime(currentTimestamp);
                    lastPublishInfo.setState(StripeMonetizationConstants.COMPLETED);
                    lastPublishInfo.setStatus(StripeMonetizationConstants.SUCCESSFULL);
                    apiAdmin.updateMonetizationUsagePublishInfo(lastPublishInfo);
                } catch (APIManagementException ex) {
                    String msg = "Failed to update last published time ";
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(msg, ex);
                }
                return true;
            }

            for (StringTermsBucket apiIdBucketObj : apiIdBuckets) {
                apiUuid = apiIdBucketObj.key();
                List<StringTermsBucket> tenantBasedBuckets = apiIdBucketObj.aggregations().get(TENANT_DOMAIN_COL)
                        .sterms().buckets().array();
                for (StringTermsBucket tenantBasedBucket : tenantBasedBuckets) {
                    tenantDomain = tenantBasedBucket.key();

                    List<StringTermsBucket> appIdBuckets = tenantBasedBucket.aggregations()
                            .get(APPLICATION_ID_COLUMN).sterms().buckets().array();
                    for (StringTermsBucket appIdBucketObj : appIdBuckets) {
                        String applicationUuid = appIdBucketObj.key();
                        requestCount = appIdBucketObj.docCount();

                        try {
                            APIInfo api1 = apiMgtDAO.getAPIInfoByUUID(apiUuid);
                            apiName = api1.getName();
                            apiProvider = api1.getProvider();
                            Application app = apiMgtDAO.getApplicationByUUID(applicationUuid);
                            applicationId = app.getId();

                            try {
                                //get the billing engine subscription details
                                MonetizedSubscription subscription = stripeMonetizationDAO
                                        .getMonetizedSubscription(apiUuid, apiName, applicationId, tenantDomain);
                                if (subscription.getSubscriptionId() != null) {
                                    try {
                                        //start the tenant flow to get the platform key
                                        PrivilegedCarbonContext.startTenantFlow();
                                        PrivilegedCarbonContext.getThreadLocalCarbonContext()
                                                .setTenantDomain(tenantDomain, true);
                                        //read tenant conf and get platform account key
                                        Stripe.apiKey = getStripePlatformAccountKey(tenantDomain);
                                    } catch (StripeMonetizationException e) {
                                        String errorMessage = "Failed to get Stripe platform account key for tenant :" +
                                                "  " + tenantDomain + " when disabling monetization for : " + apiName;
                                        //throw MonetizationException as it will be logged and handled by the caller
                                        throw new MonetizationException(errorMessage, e);
                                    } finally {
                                        PrivilegedCarbonContext.endTenantFlow();
                                    }
                                    String connectedAccountKey;
                                    try {
                                        PrivilegedCarbonContext.startTenantFlow();
                                        PrivilegedCarbonContext.getThreadLocalCarbonContext()
                                                .setTenantDomain(tenantDomain, true);
                                        apiProvider = APIUtil.replaceEmailDomain(apiProvider);
                                        APIProvider apiProvider1 = APIManagerFactory
                                                .getInstance().getAPIProvider(apiProvider);
                                        API api = apiProvider1.getAPIbyUUID(apiUuid, tenantDomain);
                                        Map<String, String> monetizationProperties = new Gson()
                                                .fromJson(api.getMonetizationProperties().toString(), HashMap.class);
                                        //get api publisher's stripe key (i.e - connected account key) from monetization
                                        // properties in request payload
                                        if (MapUtils.isNotEmpty(monetizationProperties) && monetizationProperties
                                                .containsKey(StripeMonetizationConstants
                                                        .BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                                            connectedAccountKey = monetizationProperties
                                                    .get(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
                                            if (StringUtils.isBlank(connectedAccountKey)) {
                                                String errorMessage = "Connected account stripe key was not found for : "
                                                        + api.getId().getApiName();
                                                //throw MonetizationException as it will be logged and handled by the caller
                                                throw new MonetizationException(errorMessage);
                                            }
                                        } else {
                                            String errorMessage = "Stripe key of the connected account is empty.";
                                            //throw MonetizationException as it will be logged and handled by the caller
                                            throw new MonetizationException(errorMessage);
                                        }
                                    } catch (APIManagementException e) {
                                        String errorMessage = "Failed to get the Stripe key of the connected account from "
                                                + "the : " + apiName;
                                        //throw MonetizationException as it will be logged and handled by the caller
                                        throw new MonetizationException(errorMessage, e);
                                    } finally {
                                        PrivilegedCarbonContext.endTenantFlow();
                                    }
                                    RequestOptions subRequestOptions = RequestOptions.builder()
                                            .setStripeAccount(connectedAccountKey).build();
                                    Subscription sub = Subscription.retrieve(subscription.getSubscriptionId(),
                                            subRequestOptions);
                                    //get the first subscription item from the array
                                    subscriptionItem = sub.getItems().getData().get(0);
                                    //check whether the billing plan is Usage Based.
                                    if (subscriptionItem.getPlan().getUsageType()
                                            .equals(StripeMonetizationConstants.METERED_PLAN)) {
                                        flag++;
                                        Map<String, Object> usageRecordParams = new HashMap<String, Object>();
                                        usageRecordParams.put(StripeMonetizationConstants.QUANTITY, requestCount);
                                        //provide the timesatmp in second format
                                        usageRecordParams.put(StripeMonetizationConstants.TIMESTAMP,
                                                currentTimestamp / 1000);
                                        usageRecordParams.put(StripeMonetizationConstants.ACTION,
                                                StripeMonetizationConstants.INCREMENT);
                                        RequestOptions usageRequestOptions = RequestOptions.builder()
                                                .setStripeAccount(connectedAccountKey)
                                                .setIdempotencyKey(subscriptionItem.getId()
                                                        + lastPublishInfo.getLastPublishTime() + requestCount).build();
                                        UsageRecord usageRecord = UsageRecord.createOnSubscriptionItem(
                                                subscriptionItem.getId(), usageRecordParams, usageRequestOptions);
                                        //checks whether the usage record is published successfully
                                        if (usageRecord.getId() != null) {
                                            counter++;
                                            if (log.isDebugEnabled()) {
                                                String msg = "Usage for " + apiName + " by Application with ID "
                                                        + applicationId + " is successfully published to Stripe";
                                                log.debug(msg);
                                            }
                                        }
                                    }
                                }
                            } catch (StripeMonetizationException e) {
                                String errorMessage = "Unable to Publish usage Record to Billing Engine";
                                //throw MonetizationException as it will be logged and handled by the caller
                                throw new MonetizationException(errorMessage, e);
                            } catch (StripeException e) {
                                String errorMessage = "Unable to Publish usage Record";
                                //throw MonetizationException as it will be logged and handled by the caller
                                throw new MonetizationException(errorMessage, e);
                            }
                        } catch (APIManagementException e) {
                            throw new MonetizationException("Error occurred while retrieving application details ", e);
                        }
                    }
                }
            }
        } else {
            LinkedTreeMap<String, ArrayList<LinkedTreeMap<String, String>>> data = getUsageData(formattedFromDate,
                    formattedToDate);
            ArrayList<LinkedTreeMap<String, String>> usageResponse = new ArrayList<>();
            if (data != null) {
                usageResponse = data.get((useNewQueryAPI) ?
                        StripeMonetizationConstants.GET_USAGE_BY_APPLICATION_WITH_ON_PREM_KEY
                        : StripeMonetizationConstants.GET_USAGE_BY_APPLICATION);
            }
            if (usageResponse.isEmpty()) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("No API Usage retrived for the given period of time");
                    }
                    //last publish time will be updated as successfully since there was no usage retrieved.
                    lastPublishInfo.setLastPublishTime(currentTimestamp);
                    lastPublishInfo.setState(StripeMonetizationConstants.COMPLETED);
                    lastPublishInfo.setStatus(StripeMonetizationConstants.SUCCESSFULL);
                    apiAdmin.updateMonetizationUsagePublishInfo(lastPublishInfo);
                } catch (APIManagementException ex) {
                    String msg = "Failed to update last published time ";
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(msg, ex);
                }
                return true;
            }
            for (Map.Entry<String, ArrayList<LinkedTreeMap<String, String>>> entry : data.entrySet()) {
                String key = entry.getKey();
                ArrayList<LinkedTreeMap<String, String>> apiUsageDataCollection = entry.getValue();
                for (LinkedTreeMap<String, String> apiUsageData : apiUsageDataCollection) {
                    apiUuid = apiUsageData.get(API_UUID);
                    apiName = apiUsageData.get(StripeMonetizationConstants.API_NAME);
                    apiVersion = apiUsageData.get(StripeMonetizationConstants.API_VERSION);
                    tenantDomain = apiUsageData.get(StripeMonetizationConstants.TENANT_DOMAIN);
                    applicationName = apiUsageData.get(StripeMonetizationConstants.APPLICATION_NAME);
                    applicationOwner = apiUsageData.get(StripeMonetizationConstants.APPLICATION_OWNER);
                    try {
                        applicationId = apiMgtDAO.getApplicationId(applicationName, applicationOwner);
                        apiProvider = apiMgtDAO.getAPIProviderByNameAndVersion(apiName, apiVersion, tenantDomain);
                    } catch (APIManagementException e) {
                        throw new MonetizationException("Error while retrieving Application Id for " +
                                "Application " + applicationName, e);
                    }
                    requestCount = Long.parseLong(apiUsageData.get(StripeMonetizationConstants.COUNT));
                    try {
                        //get the billing engine subscription details
                        MonetizedSubscription subscription = stripeMonetizationDAO
                                .getMonetizedSubscription(apiUuid, apiName, applicationId,
                                        tenantDomain);
                        if (subscription.getSubscriptionId() != null) {
                            try {
                                //start the tenant flow to get the platform key
                                PrivilegedCarbonContext.startTenantFlow();
                                PrivilegedCarbonContext.getThreadLocalCarbonContext().
                                        setTenantDomain(tenantDomain, true);
                                //read tenant conf and get platform account key
                                Stripe.apiKey = getStripePlatformAccountKey(tenantDomain);
                            } catch (StripeMonetizationException e) {
                                String errorMessage = "Failed to get Stripe platform account key for tenant :  " +
                                        tenantDomain + " when disabling monetization for : " + apiName;
                                //throw MonetizationException as it will be logged and handled by the caller
                                throw new MonetizationException(errorMessage, e);
                            } finally {
                                PrivilegedCarbonContext.endTenantFlow();
                            }
                            String connectedAccountKey;
                            try {
                                PrivilegedCarbonContext.startTenantFlow();
                                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(
                                        tenantDomain, true);
                                apiProvider = APIUtil.replaceEmailDomain(apiProvider);
                                APIProvider apiProvider1 = APIManagerFactory.getInstance().getAPIProvider(apiProvider);
                                API api = apiProvider1.getAPIbyUUID(apiUuid, tenantDomain);
                                Map<String, String> monetizationProperties = new Gson().fromJson(
                                        api.getMonetizationProperties().toString(), HashMap.class);
                                //get api publisher's stripe key (i.e - connected account key) from monetization
                                // properties in request payload
                                if (MapUtils.isNotEmpty(monetizationProperties) &&
                                        monetizationProperties.containsKey(
                                                StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                                    connectedAccountKey = monetizationProperties.get
                                            (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
                                    if (StringUtils.isBlank(connectedAccountKey)) {
                                        String errorMessage = "Connected account stripe key was not found for : "
                                                + api.getId().getApiName();
                                        //throw MonetizationException as it will be logged and handled by the caller
                                        throw new MonetizationException(errorMessage);
                                    }
                                } else {
                                    String errorMessage = "Stripe key of the connected account is empty.";
                                    //throw MonetizationException as it will be logged and handled by the caller
                                    throw new MonetizationException(errorMessage);
                                }
                            } catch (APIManagementException e) {
                                String errorMessage = "Failed to get the Stripe key of the connected account from "
                                        + "the : " + apiName;
                                //throw MonetizationException as it will be logged and handled by the caller
                                throw new MonetizationException(errorMessage, e);
                            } finally {
                                PrivilegedCarbonContext.endTenantFlow();
                            }
                            RequestOptions subRequestOptions = RequestOptions.builder().
                                    setStripeAccount(connectedAccountKey).build();
                            Subscription sub = Subscription.retrieve(subscription.getSubscriptionId(),
                                    subRequestOptions);
                            //get the first subscription item from the array
                            subscriptionItem = sub.getItems().getData().get(0);
                            //check whether the billing plan is Usage Based.
                            if (subscriptionItem.getPlan().getUsageType().equals(
                                    StripeMonetizationConstants.METERED_PLAN)) {
                                flag++;
                                Map<String, Object> usageRecordParams = new HashMap<String, Object>();
                                usageRecordParams.put(StripeMonetizationConstants.QUANTITY, requestCount);
                                //provide the timesatmp in second format
                                usageRecordParams.put(StripeMonetizationConstants.TIMESTAMP,
                                        currentTimestamp / 1000);
                                usageRecordParams.put(StripeMonetizationConstants.ACTION,
                                        StripeMonetizationConstants.INCREMENT);
                                RequestOptions usageRequestOptions = RequestOptions.builder().
                                        setStripeAccount(connectedAccountKey).setIdempotencyKey(subscriptionItem.getId()
                                                + lastPublishInfo.getLastPublishTime() + requestCount).build();
                                UsageRecord usageRecord = UsageRecord.createOnSubscriptionItem(
                                        subscriptionItem.getId(), usageRecordParams, usageRequestOptions);
                                //checks whether the usage record is published successfully
                                if (usageRecord.getId() != null) {
                                    counter++;
                                    if (log.isDebugEnabled()) {
                                        String msg = "Usage for " + apiName + " by Application with ID " + applicationId
                                                + " is successfully published to Stripe";
                                        log.debug(msg);
                                    }
                                }
                            }
                        }
                    } catch (StripeMonetizationException e) {
                        String errorMessage = "Unable to Publish usage Record to Billing Engine";
                        //throw MonetizationException as it will be logged and handled by the caller
                        throw new MonetizationException(errorMessage, e);
                    } catch (StripeException e) {
                        String errorMessage = "Unable to Publish usage Record";
                        //throw MonetizationException as it will be logged and handled by the caller
                        throw new MonetizationException(errorMessage, e);
                    }
                }
            }
        }

        //Flag equals counter when all the records are published successfully
        if (flag == counter) {
            try {
                //last publish time will be updatedr only if all the records are successfull
                lastPublishInfo.setLastPublishTime(currentTimestamp);
                lastPublishInfo.setState(StripeMonetizationConstants.COMPLETED);
                lastPublishInfo.setStatus(StripeMonetizationConstants.SUCCESSFULL);
                apiAdmin.updateMonetizationUsagePublishInfo(lastPublishInfo);
            } catch (APIManagementException ex) {
                String msg = "Failed to update last published time ";
                //throw MonetizationException as it will be logged and handled by the caller
                throw new MonetizationException(msg, ex);
            }
            return true;
        }
        try {
            lastPublishInfo.setState(StripeMonetizationConstants.COMPLETED);
            lastPublishInfo.setStatus(StripeMonetizationConstants.UNSUCCESSFULL);
            apiAdmin.updateMonetizationUsagePublishInfo(lastPublishInfo);
        } catch (APIManagementException ex) {
            String msg = "Failed to update last published time ";
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(msg, ex);
        }
        return false;
    }

    /**
     * Get usage data for all monetized APIs from Elasticsearch between the given time.
     *
     * @param formattedFromDate The starting date of the time range
     * @param formattedToDate   The ending date of the time range
     * @return usage data of monetized APIs
     * @throws MonetizationException if failed to get the usage for the APIs
     */
    private SearchResponse<Object> getUsageDataFromElasticsearch(String formattedFromDate, String formattedToDate)
            throws MonetizationException {

        String username = config.getMonetizationConfigurationDto().getAnalyticsUserName();
        byte[] password = config.getMonetizationConfigurationDto().getAnalyticsPassword();
        String hostname = config.getMonetizationConfigurationDto().getAnalyticsHost();
        String analyticsIndex = config.getMonetizationConfigurationDto().getAnalyticsIndexName();
        if (analyticsIndex == null) {
            analyticsIndex = DEFAULT_ELK_ANALYTICS_INDEX;
        }
        int port = config.getMonetizationConfigurationDto().getAnalyticsPort();
        List<JSONArray> tenantDomainsAndAPIs = getMonetizedAPIIdsAndTenantDomains();
        JSONArray tenants = tenantDomainsAndAPIs.get(0);
        JSONArray monetizedAPIs = tenantDomainsAndAPIs.get(1);
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username,
                new String(password, StandardCharsets.UTF_8)));

        if (tenantDomainsAndAPIs.size() == 2) {
            List<FieldValue> tenantList = new ArrayList<>();
            List<FieldValue> monetizedAPIsList = new ArrayList<>();
            for (Object tenant : tenants) {
                tenantList.add(new FieldValue.Builder().stringValue((String) tenant).build());
            }
            for (Object api : monetizedAPIs) {
                monetizedAPIsList.add(new FieldValue.Builder().stringValue((String) api).build());
            }
            try (RestClient restClient = RestClient.builder(new HttpHost(hostname, port))
                    .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                            .setDefaultCredentialsProvider(credentialsProvider)).build()) {
                ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
                ElasticsearchClient elasticsearchClient = new ElasticsearchClient(transport);

                Query query = BoolQuery.of(b -> b
                        .must(QueryBuilders.range(r -> r.field(REQUEST_TIMESTAMP_COLUMN)
                                .from(formattedFromDate).to(formattedToDate)))
                        .must(QueryBuilders.terms(m -> m.field(ELK_API_ID_COL)
                                .terms(t -> t.value(monetizedAPIsList))))
                        .must(QueryBuilders.terms(l -> l.field(ELK_TENANT_DOMAIN)
                                .terms(t -> t.value(tenantList)))))._toQuery();

                SearchRequest searchRequest = new SearchRequest.Builder()
                        .index(analyticsIndex)
                        .query(query)
                        .aggregations(API_UUID, a -> a
                                .terms(TermsAggregation.of(t -> t
                                        .field(ELK_API_ID_COL)))
                                .aggregations(TENANT_DOMAIN_COL, b -> b
                                        .terms(TermsAggregation.of(t -> t
                                                .field(ELK_TENANT_DOMAIN)))
                                        .aggregations(APPLICATION_ID_COLUMN, c -> c
                                                .terms(TermsAggregation.of(t -> t
                                                        .field(ELK_APPLICATION_ID_COLUMN))))))
                        .source(s -> s.fetch(false)).size(0).build();

                return elasticsearchClient.search(searchRequest, Object.class);
            } catch (IOException e) {
                throw new MonetizationException("Error occurred while executing data retrieval from Elasticsearch", e);
            }
        } else {
            try (RestClient restClient = RestClient.builder(new HttpHost(hostname, port))
                    .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                            .setDefaultCredentialsProvider(credentialsProvider)).build()) {
                ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
                ElasticsearchClient elasticsearchClient = new ElasticsearchClient(transport);
                Query query = RangeQuery.of(r -> r.field(REQUEST_TIMESTAMP_COLUMN)
                                .from(formattedFromDate)
                                .to(formattedToDate))
                        ._toQuery();

                SearchRequest searchRequest = new SearchRequest.Builder()
                        .index(analyticsIndex)
                        .query(query)
                        .aggregations(API_UUID, a -> a.terms(
                                        TermsAggregation.of(t -> t.field(ELK_API_ID_COL)))
                                .aggregations(APPLICATION_ID_COLUMN, b -> b.terms(
                                        TermsAggregation.of(t -> t.field(ELK_APPLICATION_ID_COLUMN)))))
                        .source(s -> s.fetch(false)).size(0).build();
                return elasticsearchClient.search(searchRequest, Object.class);
            } catch (IOException e) {
                throw new MonetizationException("Error occurred while executing data retrieval from Elasticsearch", e);
            }
        }
    }

    /**
     * Get usage data for all monetized APIs from Choreo Analytics between the given time.
     *
     * @param formattedFromDate The starting date of the time range
     * @param formattedToDate   The ending date of the time range
     * @return usage data of monetized APIs
     * @throws MonetizationException if failed to get the usage for the APIs
     */
    LinkedTreeMap<String, ArrayList<LinkedTreeMap<String, String>>> getUsageData(String formattedFromDate,
                                                                                 String formattedToDate)
            throws MonetizationException {

        String queryApiEndpoint = config.getMonetizationConfigurationDto().getInsightAPIEndpoint();
        String onPremKey = config.getMonetizationConfigurationDto().getAnalyticsAccessToken();
        if (StringUtils.isEmpty(queryApiEndpoint) || StringUtils.isEmpty(onPremKey)) {
            // Since on prem key is required for both query APIs, it has been made mandatory
            throw new MonetizationException(
                    "Endpoint or analytics access token for the the analytics query api is not configured");
        }

        String accessToken;
        if (MonetizationDataHolder.getInstance().getMonetizationAccessTokenGenerator() != null) {
            accessToken = MonetizationDataHolder.getInstance().getMonetizationAccessTokenGenerator().getAccessToken();
            if (StringUtils.isEmpty(accessToken)) {
                throw new MonetizationException(
                        "Cannot retrieve access token from the provided token url");
            }
            useNewQueryAPI = true;
        } else {
            accessToken = onPremKey;
            useNewQueryAPI = false;
        }

        JSONObject timeFilter = new JSONObject();
        timeFilter.put(StripeMonetizationConstants.FROM, formattedFromDate);
        timeFilter.put(StripeMonetizationConstants.TO, formattedToDate);

        List<JSONArray> tenantsAndApis = getMonetizedAPIIdsAndTenantDomains();

        if (tenantsAndApis.size() == 2) {
            if (tenantsAndApis.get(1).size() > 0) {
                JSONObject successAPIUsageByAppFilter = new JSONObject();
                successAPIUsageByAppFilter.put(StripeMonetizationConstants.API_ID_COL, tenantsAndApis.get(1));
                successAPIUsageByAppFilter.put(TENANT_DOMAIN_COL, tenantsAndApis.get(0));
                JSONObject variables = new JSONObject();
                variables.put(StripeMonetizationConstants.TIME_FILTER, timeFilter);
                variables.put(StripeMonetizationConstants.API_USAGE_BY_APP_FILTER, successAPIUsageByAppFilter);
                if (useNewQueryAPI) {
                    variables.put(StripeMonetizationConstants.ON_PREM_KEY, onPremKey);
                }
                GraphQLClient graphQLClient =
                        Feign.builder().client(new OkHttpClient()).encoder(new GsonEncoder()).decoder(new GsonDecoder())
                                .logger(new Slf4jLogger())
                                .requestInterceptor(new QueyAPIAccessTokenInterceptor(accessToken))
                                .target(GraphQLClient.class, queryApiEndpoint);
                GraphqlQueryModel queryModel = new GraphqlQueryModel();
                queryModel.setQuery(getGraphQLQueryBasedOnTheOperationMode(useNewQueryAPI));
                queryModel.setVariables(variables);
                graphQLResponseClient usageResponse = graphQLClient.getSuccessAPIsUsageByApplications(queryModel);
                return usageResponse.getData();
            }
        }
        return null;
    }

    public List<JSONArray> getMonetizedAPIIdsAndTenantDomains() throws MonetizationException {

        JSONArray monetizedAPIIdsList = new JSONArray();
        JSONArray tenantDomainList = new JSONArray();
        List<JSONArray> tenantsAndApis = new ArrayList<>(2);
        try {
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
            List<Tenant> tenants = APIUtil.getAllTenantsWithSuperTenant();
            for (Tenant tenant : tenants) {
                tenantDomainList.add(tenant.getDomain());
                try {
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(
                            tenant.getDomain(), true);
                    String tenantAdminUsername = APIUtil.getAdminUsername();
                    if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenant.getDomain())) {
                        tenantAdminUsername =
                                APIUtil.getAdminUsername() + StripeMonetizationConstants.AT + tenant.getDomain();
                    }
                    APIProvider apiProviderNew = APIManagerFactory.getInstance().getAPIProvider(tenantAdminUsername);
                    List<API> allowedAPIs = apiProviderNew.getAllAPIs();
                    Organization org = new Organization(tenant.getDomain());
                    for (API api : allowedAPIs) {
                        PublisherAPI publisherAPI = null;
                        try {
                            publisherAPI = apiPersistenceInstance.getPublisherAPI(org, api.getUUID());
                            if (publisherAPI.isMonetizationEnabled()) {
                                monetizedAPIIdsList.add(api.getUUID());
                            }
                        } catch (APIPersistenceException e) {
                            throw new MonetizationException("Failed to retrieve the API of UUID: " + api.getUUID(), e);
                        }
                    }
                } catch (APIManagementException e) {
                    throw new MonetizationException("Error while retrieving the Ids of Monetized APIs");
                }
            }
        } catch (UserStoreException e) {
            throw new MonetizationException("Error while retrieving the tenants", e);
        }
        tenantsAndApis.add(tenantDomainList);
        tenantsAndApis.add(monetizedAPIIdsList);
        return tenantsAndApis;
    }

    public String getGraphQLQueryBasedOnTheOperationMode(boolean useNewQueryAPI) {

        if (useNewQueryAPI) {
            return "query($onPremKey: String!, $timeFilter: TimeFilter!, " +
                    "$successAPIUsageByAppFilter: SuccessAPIUsageByAppFilter!) " +
                    "{getSuccessAPIsUsageByApplicationsWithOnPremKey(onPremKey:$onPremKey, timeFilter: $timeFilter, " +
                    "successAPIUsageByAppFilter: $successAPIUsageByAppFilter) { apiId apiName apiVersion " +
                    "apiCreatorTenantDomain applicationId applicationName applicationOwner count}}";
        } else {
            return "query($timeFilter: TimeFilter!, " +
                    "$successAPIUsageByAppFilter: SuccessAPIUsageByAppFilter!) " +
                    "{getSuccessAPIsUsageByApplications(timeFilter: $timeFilter, " +
                    "successAPIUsageByAppFilter: $successAPIUsageByAppFilter) { apiId apiName apiVersion " +
                    "apiCreatorTenantDomain applicationId applicationName applicationOwner count}}";
        }
    }

    /**
     * Get current usage for a subscription
     *
     * @param subscriptionUUID subscription UUID
     * @param apiProvider      API provider
     * @return current usage for a subscription
     * @throws MonetizationException if failed to get current usage for a subscription
     */
    public Map<String, String> getCurrentUsageForSubscription(String subscriptionUUID, APIProvider apiProvider)
            throws MonetizationException {

        Map<String, String> billingEngineUsageData = new HashMap<String, String>();
        String apiName = null;
        try (Connection con = APIMgtDBUtil.getConnection()) {
            SubscribedAPI subscribedAPI = ApiMgtDAO.getInstance().getSubscriptionByUUID(subscriptionUUID);
            APIIdentifier apiIdentifier = subscribedAPI.getApiId();
            APIProductIdentifier apiProductIdentifier;
            API api;
            APIProduct apiProduct;
            HashMap monetizationDataMap;
            int apiId;
            if (apiIdentifier != null) {
                api = apiProvider.getAPIbyUUID(apiIdentifier.getUUID(), apiIdentifier.getOrganization());
                apiName = apiIdentifier.getApiName();
                if (api.getMonetizationProperties() == null) {
                    String errorMessage = "Monetization properties are empty for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
                monetizationDataMap = new Gson().fromJson(api.getMonetizationProperties().toString(), HashMap.class);
                if (MapUtils.isEmpty(monetizationDataMap)) {
                    String errorMessage = "Monetization data map is empty for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
                apiId = ApiMgtDAO.getInstance().getAPIID(api.getUuid(), con);
            } else {
                apiProductIdentifier = subscribedAPI.getProductId();
                apiProduct = apiProvider.getAPIProduct(apiProductIdentifier);
                apiName = apiProductIdentifier.getName();
                if (apiProduct.getMonetizationProperties() == null) {
                    String errorMessage = "Monetization properties are empty for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
                monetizationDataMap = new Gson().fromJson(apiProduct.getMonetizationProperties().toString(),
                        HashMap.class);
                if (MapUtils.isEmpty(monetizationDataMap)) {
                    String errorMessage = "Monetization data map is empty for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
                apiId = ApiMgtDAO.getInstance().getAPIProductId(apiProductIdentifier);
            }
            String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
            //get billing engine platform account key
            String platformAccountKey = getStripePlatformAccountKey(tenantDomain);
            if (monetizationDataMap.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                String connectedAccountKey = monetizationDataMap.get
                        (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY).toString();
                if (StringUtils.isBlank(connectedAccountKey)) {
                    String errorMessage = "Connected account stripe key was not found for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
                Stripe.apiKey = platformAccountKey;
                //create request options to link with the connected account
                RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
                int applicationId = subscribedAPI.getApplication().getId();
                String billingPlanSubscriptionId = stripeMonetizationDAO.getBillingEngineSubscriptionId(apiId,
                        applicationId);
                Subscription billingEngineSubscription = Subscription.retrieve(billingPlanSubscriptionId,
                        requestOptions);
                if (billingEngineSubscription == null) {
                    String errorMessage = "No billing engine subscription was found for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
                // In Stripe SDK 24.x, Subscription.getPlan() was removed.
                // The plan is now accessed through the subscription's first item.
                String usageType = billingEngineSubscription.getItems().getData().get(0).getPlan().getUsageType();
                boolean dynamicUsage = StripeMonetizationConstants.METERED_USAGE.equalsIgnoreCase(usageType);
                boolean fixedRate = StripeMonetizationConstants.LICENSED_USAGE.equalsIgnoreCase(usageType);
                if (!dynamicUsage && !fixedRate) {
                    String errorMsg = "Usage type should be set to 'metered' or 'licensed' to get the pending bill.";
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMsg);
                }
                Map<String, Object> invoiceParams = new HashMap<String, Object>();
                invoiceParams.put("subscription", billingEngineSubscription.getId());
                //fetch the upcoming invoice
                Invoice invoice = Invoice.upcoming(invoiceParams, requestOptions);
                if (invoice == null) {
                    String errorMessage = "No billing engine subscription was found for : " + apiName;
                    //throw MonetizationException as it will be logged and handled by the caller
                    throw new MonetizationException(errorMessage);
                }
                SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
                dateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
                //the below parameters are billing engine specific
                billingEngineUsageData.put("Description", invoice.getDescription());
                billingEngineUsageData.put("Paid", invoice.getPaid() != null ? invoice.getPaid().toString() : null);
                billingEngineUsageData.put("Tax", invoice.getTax() != null ?
                        invoice.getTax().toString() : null);
                billingEngineUsageData.put("Invoice ID", invoice.getId());
                billingEngineUsageData.put("Account Name", invoice.getAccountName());
                billingEngineUsageData.put("Next Payment Attempt", invoice.getNextPaymentAttempt() != null ?
                        dateFormatter.format(new Date(invoice.getNextPaymentAttempt() * 1000)) : null);
                billingEngineUsageData.put("Customer Email", invoice.getCustomerEmail());
                billingEngineUsageData.put("Currency", invoice.getCurrency());
                billingEngineUsageData.put("Account Country", invoice.getAccountCountry());
                billingEngineUsageData.put("Amount Remaining", invoice.getAmountRemaining() != null ?
                        Long.toString(invoice.getAmountRemaining() / 100L) : null);
                billingEngineUsageData.put("Period End", invoice.getPeriodEnd() != null ?
                        dateFormatter.format(new Date(invoice.getPeriodEnd() * 1000)) : null);
                billingEngineUsageData.put("Due Date", invoice.getDueDate() != null ?
                        dateFormatter.format(new Date(invoice.getDueDate())) : null);
                billingEngineUsageData.put("Amount Due", invoice.getAmountDue() != null ?
                        Long.toString(invoice.getAmountDue() / 100L) : null);
                billingEngineUsageData.put("Total Tax Amounts", invoice.getTotalTaxAmounts() != null ?
                        invoice.getTotalTaxAmounts().toString() : null);
                billingEngineUsageData.put("Amount Paid", invoice.getAmountPaid() != null ?
                        Long.toString(invoice.getAmountPaid() / 100L) : null);
                billingEngineUsageData.put("Subtotal", invoice.getSubtotal() != null ?
                        Long.toString(invoice.getSubtotal() / 100L) : null);
                billingEngineUsageData.put("Total", invoice.getTotal() != null ?
                        Long.toString(invoice.getTotal() / 100L) : null);
                billingEngineUsageData.put("Period Start", invoice.getPeriodStart() != null ?
                        dateFormatter.format(new Date(invoice.getPeriodStart() * 1000)) : null);

                //the below parameters are also returned from stripe, but commented for simplicity of the invoice
                /*billingEngineUsageData.put("object", "invoice");
                billingEngineUsageData.put("Application Fee Amount", invoice.getApplicationFeeAmount() != null ?
                        invoice.getApplicationFeeAmount().toString() : null);
                billingEngineUsageData.put("Attempt Count", invoice.getAttemptCount() != null ?
                        invoice.getAttemptCount().toString() : null);
                billingEngineUsageData.put("Attempted", invoice.getAttempted() != null ?
                        invoice.getAttempted().toString() : null);
                billingEngineUsageData.put("Billing", invoice.getBilling());
                billingEngineUsageData.put("Billing Reason", invoice.getBillingReason());
                billingEngineUsageData.put("Charge", invoice.getCharge());
                billingEngineUsageData.put("Created", invoice.getCreated() != null ? invoice.getCreated().toString() : null);
                billingEngineUsageData.put("Customer", invoice.getCustomer());
                billingEngineUsageData.put("Customer Address", invoice.getCustomerAddress() != null ?
                        invoice.getCustomerAddress().toString() : null);
                billingEngineUsageData.put("Customer Name", invoice.getCustomerName());
                billingEngineUsageData.put("Ending Balance", invoice.getEndingBalance() != null ?
                        invoice.getEndingBalance().toString() : null);
                billingEngineUsageData.put("Livemode", invoice.getLivemode() != null ? invoice.getLivemode().toString() : null);
                billingEngineUsageData.put("Number", invoice.getNumber());
                billingEngineUsageData.put("Payment Intent", invoice.getPaymentIntent());
                billingEngineUsageData.put("Post Payment Credit Notes Amount",
                        invoice.getPostPaymentCreditNotesAmount() != null ? invoice.getPostPaymentCreditNotesAmount().toString() : null);
                billingEngineUsageData.put("Pre Payment Credit Notes Amount",
                        invoice.getPrePaymentCreditNotesAmount() != null ? invoice.getPrePaymentCreditNotesAmount().toString() : null);
                billingEngineUsageData.put("Receipt Number", invoice.getReceiptNumber());
                billingEngineUsageData.put("Subscription", invoice.getSubscription());
                billingEngineUsageData.put("Tax Percent", invoice.getTaxPercent() != null ?
                        invoice.getTaxPercent().toString() : null);*/
            }
        } catch (StripeException e) {
            String errorMessage = "Error while fetching billing engine usage data for : " + apiName;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscription details of : " + apiName;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get billing engine data for subscription : " + subscriptionUUID;
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new MonetizationException(errorMessage, e);
        }
        return billingEngineUsageData;
    }

    /**
     * Get total revenue for a given API from all subscriptions
     *
     * @param api         API
     * @param apiProvider API provider
     * @return total revenue data for a given API from all subscriptions
     * @throws MonetizationException if failed to get total revenue data for a given API
     */
    public Map<String, String> getTotalRevenue(API api, APIProvider apiProvider) throws MonetizationException {

        APIIdentifier apiIdentifier = api.getId();
        Map<String, String> revenueData = new HashMap<String, String>();
        try {
            //get all subscriptions for the API
            List<SubscribedAPI> apiUsages = apiProvider.getAPIUsageByAPIId(api.getUuid(),
                    api.getId().getOrganization());
            for (SubscribedAPI subscribedAPI : apiUsages) {
                //get subscription UUID for each subscription
                int subscriptionId = subscribedAPI.getSubscriptionId();
                String subscriptionUUID = stripeMonetizationDAO.getSubscriptionUUID(subscriptionId);
                //get revenue for each subscription and add them
                Map<String, String> billingEngineUsageData = getCurrentUsageForSubscription(subscriptionUUID,
                        apiProvider);
                revenueData.put("Revenue for subscription ID : " + subscriptionId,
                        billingEngineUsageData.get("amount_due"));
            }
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscriptions of : " + apiIdentifier.getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to get subscription UUID of : " + apiIdentifier.getApiName();
            //throw MonetizationException as it will be logged and handled by the caller
            throw new MonetizationException(errorMessage, e);
        }
        return revenueData;
    }

    /**
     * This method is used to get stripe platform account key for a given tenant
     *
     * @param tenantDomain tenant domain
     * @return stripe platform account key for the given tenant
     * @throws StripeMonetizationException if it fails to get stripe platform account key for the given tenant
     */
    private String getStripePlatformAccountKey(String tenantDomain) throws StripeMonetizationException {

        try {
            //get the stripe key of platform account from  tenant conf json file
            JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
            if (tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
                JSONObject monetizationInfo = (JSONObject) tenantConfig
                        .get(StripeMonetizationConstants.MONETIZATION_INFO);
                if (monetizationInfo.containsKey(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)) {
                    String stripePlatformAccountKey = monetizationInfo
                            .get(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
                    if (StringUtils.isBlank(stripePlatformAccountKey)) {
                        String errorMessage = "Stripe platform account key is empty for tenant : " + tenantDomain;
                        throw new StripeMonetizationException(errorMessage);
                    }
                    return stripePlatformAccountKey;
                }
            }
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get the configuration for tenant from DB:  " + tenantDomain;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        }
        return StringUtils.EMPTY;
    }

    /**
     * Get billing product ID for a given API
     *
     * @param apiId API ID
     * @return billing product ID for the given API
     * @throws StripeMonetizationException if failed to get billing product ID for the given API
     */
    private String getBillingProductIdForApi(int apiId) throws StripeMonetizationException {

        String billingProductId = StringUtils.EMPTY;
        billingProductId = stripeMonetizationDAO.getBillingEngineProductId(apiId);
        return billingProductId;
    }

    /**
     * Get billing plan ID for a given tier
     *
     * @param apiId    API ID
     * @param tierName tier name
     * @return billing plan ID for a given tier
     * @throws StripeMonetizationException if failed to get billing plan ID for the given tier
     */
    private String getBillingPlanIdOfTier(int apiId, String tierName) throws StripeMonetizationException {

        String billingPlanId = StringUtils.EMPTY;
        billingPlanId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(apiId, tierName);
        return billingPlanId;
    }

    /**
     * Create billing plan for a given commercial tier
     *
     * @param tier                tier
     * @param tenantId            tenant ID
     * @param platformAccountKey  billing engine platform account key
     * @param connectedAccountKey billing engine connected account key
     * @param billingProductId    billing engine product ID
     * @return created plan ID in billing engine
     * @throws StripeMonetizationException if fails to create billing plan
     */
    private String createBillingPlanForCommercialTier(Tier tier, int tenantId, String platformAccountKey,
                                                      String connectedAccountKey, String billingProductId)
            throws StripeMonetizationException {

        try {
            String tierUUID = ApiMgtDAO.getInstance().getSubscriptionPolicy(tier.getName(), tenantId).getUUID();
            //get plan ID from mapping table
            String planId = stripeMonetizationDAO.getBillingPlanId(tierUUID);
            Stripe.apiKey = platformAccountKey;
            //get that plan details
            Plan billingPlan = Plan.retrieve(planId);
            //get the values from that plan and replicate it
            Map<String, Object> planParams = new HashMap<String, Object>();
            planParams.put(StripeMonetizationConstants.AMOUNT, billingPlan.getAmount());
            planParams.put(StripeMonetizationConstants.BILLING_SCHEME, billingPlan.getBillingScheme());
            planParams.put(StripeMonetizationConstants.INTERVAL, billingPlan.getInterval());
            planParams.put(StripeMonetizationConstants.PRODUCT_NICKNAME, billingPlan.getNickname());
            planParams.put(StripeMonetizationConstants.PRODUCT, billingProductId);
            planParams.put(StripeMonetizationConstants.CURRENCY, billingPlan.getCurrency());
            planParams.put(StripeMonetizationConstants.USAGE_TYPE, billingPlan.getUsageType());
            RequestOptions planRequestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
            //create a new stripe plan for the tier
            Plan createdPlan = Plan.create(planParams, planRequestOptions);
            return createdPlan.getId();
        } catch (StripeException e) {
            String errorMessage = "Unable to create billing plan for : " + tier.getName();
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get UUID for tier :  " + tier.getName();
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        }
    }

    /**
     * The method converts the date into timestamp
     *
     * @param date
     * @return Timestamp in long format
     */
    private long getTimestamp(String date) {

        SimpleDateFormat formatter = new SimpleDateFormat(StripeMonetizationConstants.TIME_FORMAT);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        long time = 0;
        Date parsedDate = null;
        try {
            parsedDate = formatter.parse(date);
            time = parsedDate.getTime();
        } catch (java.text.ParseException e) {
            log.error("Error while parsing the date ", e);
        }
        return time;
    }

    public List<API> getAllAPIs(String tenantDomain, String username) throws APIManagementException {
        Properties persistenceProperties = new Properties();
        Map<String, String> configMap = new HashMap<>();
        Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
        if (configs != null && !configs.isEmpty()) {
            configMap.putAll(configs);
        }
        configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
        APIPersistence apiPersistenceInstance = PersistenceManager
                .getPersistenceInstance(configMap, persistenceProperties);
        List<API> apiSortedList = new ArrayList<API>();
        Organization org = new Organization(tenantDomain);
        String[] roles = APIUtil.getFilteredUserRoles(username);
        Map<String, Object> properties = APIUtil.getUserProperties(username);
        UserContext userCtx = new UserContext(username, org, properties, roles);
        try {
            PublisherAPISearchResult searchAPIs = apiPersistenceInstance.searchAPIsForPublisher(org, "", 0,
                    Integer.MAX_VALUE, userCtx, null, null);

            if (searchAPIs != null) {
                List<PublisherAPIInfo> list = searchAPIs.getPublisherAPIInfoList();
                for (PublisherAPIInfo publisherAPIInfo : list) {
                    API mappedAPI = APIMapper.INSTANCE.toApi(publisherAPIInfo);
                    apiSortedList.add(mappedAPI);
                }
            }
        } catch (APIPersistenceException e) {
            throw new APIManagementException("Error while searching the api ", e);
        }

        Collections.sort(apiSortedList, new APINameComparator());
        return apiSortedList;
    }
}
