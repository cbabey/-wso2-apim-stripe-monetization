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

/**
 * This class is to define constants related to stripe based monetization
 */
public class StripeMonetizationConstants {

    /**
     * Stripe based monetization related constants
     **/
    public static final String ADD_MONETIZATION_DATA_SQL = "INSERT INTO AM_MONETIZATION VALUES (?,?,?,?)";
    public static final String DELETE_MONETIZATION_DATA_SQL = "DELETE FROM AM_MONETIZATION WHERE API_ID = ?";
    public static final String GET_BILLING_ENGINE_PRODUCT_BY_API = "SELECT STRIPE_PRODUCT_ID FROM AM_MONETIZATION WHERE API_ID = ? ";
    public static final String GET_BILLING_ENGINE_SUBSCRIPTION_ID = "SELECT SUBSCRIPTION_ID FROM " +
            "AM_MONETIZATION_SUBSCRIPTIONS " +
            "WHERE SUBSCRIBED_APPLICATION_ID = ? AND SUBSCRIBED_API_ID = ?";
    public static final String GET_BILLING_PLANS_BY_PRODUCT = "SELECT TIER_NAME, STRIPE_PLAN_ID FROM AM_MONETIZATION " +
            "WHERE API_ID = ? AND STRIPE_PRODUCT_ID = ?";
    public static final String GET_BILLING_PLAN_FOR_TIER = "SELECT STRIPE_PLAN_ID FROM AM_MONETIZATION " +
            "WHERE API_ID = ? AND TIER_NAME = ?";
    public static final String INSERT_MONETIZATION_PLAN_DATA_SQL =
            "INSERT INTO AM_POLICY_PLAN_MAPPING (POLICY_UUID, PRODUCT_ID, PLAN_ID) VALUES (?,?,?)";
    public static final String UPDATE_MONETIZATION_PLAN_ID_SQL = "UPDATE AM_POLICY_PLAN_MAPPING SET PLAN_ID = ? " +
            "WHERE POLICY_UUID = ? AND PRODUCT_ID = ?";
    public static final String DELETE_MONETIZATION_PLAN_DATA = "DELETE FROM AM_POLICY_PLAN_MAPPING WHERE " +
            "POLICY_UUID = ?";
    public static final String GET_BILLING_PLAN_DATA = "SELECT PRODUCT_ID, PLAN_ID FROM AM_POLICY_PLAN_MAPPING " +
            "WHERE POLICY_UUID = ?";
    public static final String GET_BILLING_PLAN_ID = "SELECT PLAN_ID FROM AM_POLICY_PLAN_MAPPING " +
            "WHERE POLICY_UUID = ?";
    public static final String GET_SUBSCRIPTION_UUID = "SELECT UUID FROM AM_SUBSCRIPTION WHERE SUBSCRIPTION_ID = ?";

    public static final String ADD_BE_PLATFORM_CUSTOMER_SQL =
            " INSERT" +
                    " INTO AM_MONETIZATION_PLATFORM_CUSTOMERS (SUBSCRIBER_ID, TENANT_ID, CUSTOMER_ID)" +
                    " VALUES (?,?,?)";

    public static final String ADD_BE_SHARED_CUSTOMER_SQL =
            " INSERT" +
                    " INTO AM_MONETIZATION_SHARED_CUSTOMERS (APPLICATION_ID,  API_PROVIDER," +
                    " TENANT_ID, SHARED_CUSTOMER_ID, PARENT_CUSTOMER_ID)" +
                    " VALUES (?,?,?,?,?)";

    public static final String ADD_BE_SUBSCRIPTION_SQL =
            " INSERT" +
                    " INTO AM_MONETIZATION_SUBSCRIPTIONS (SUBSCRIBED_API_ID, SUBSCRIBED_APPLICATION_ID," +
                    " TENANT_ID, SHARED_CUSTOMER_ID, SUBSCRIPTION_ID)" +
                    " VALUES ((SELECT API_ID FROM AM_API WHERE API_UUID = ?),?,?,?,?)";

    public static final String GET_BE_PLATFORM_CUSTOMER_SQL =
            "SELECT" +
                    " ID, CUSTOMER_ID" +
                    " FROM AM_MONETIZATION_PLATFORM_CUSTOMERS" +
                    " WHERE" +
                    " SUBSCRIBER_ID=? AND TENANT_ID=?";

    public static final String GET_BE_SHARED_CUSTOMER_SQL =
            " SELECT" +
                    " ID, SHARED_CUSTOMER_ID" +
                    " FROM AM_MONETIZATION_SHARED_CUSTOMERS" +
                    " WHERE" +
                    " APPLICATION_ID=? AND API_PROVIDER=? AND TENANT_ID=?";

    public static final String GET_BE_SUBSCRIPTION_SQL =
            " SELECT" +
                    " ID, SUBSCRIPTION_ID" +
                    " FROM AM_MONETIZATION_SUBSCRIPTIONS" +
                    " WHERE" +
                    " SUBSCRIBED_APPLICATION_ID=? " +
                    " AND SUBSCRIBED_API_ID=(SELECT API_ID FROM AM_API WHERE API_UUID=?)" +
                    " AND TENANT_ID=?";

    public static final String DELETE_BE_SUBSCRIPTION_SQL = "DELETE FROM AM_MONETIZATION_SUBSCRIPTIONS WHERE ID=?";

    public static final String TYPE = "type";
    public static final String SERVICE_TYPE = "service";
    public static final String CURRENCY = "currency";
    public static final String BILLING_SCHEME = "billing_scheme";
    public static final String USD = "usd";
    public static final String PRODUCT = "product";
    public static final String PRODUCT_NICKNAME = "nickname";
    public static final String INTERVAL = "interval";
    public static final String BILLING_CYCLE = "billingCycle";
    public static final String FIXED_RATE = "fixedRate";
    public static final String DYNAMIC_RATE = "dynamicRate";
    public static final String FIXED_PRICE = "fixedPrice";
    public static final String AMOUNT = "amount";
    public static final String USAGE_TYPE = "usage_type";
    public static final String LICENSED_USAGE = "licensed";
    public static final String METERED_USAGE = "metered";
    public static final String PRICE_PER_REQUEST = "pricePerRequest";
    public static final String PRODUCT_ID = "productId";
    public static final String PLAN_ID = "planId";
    public static final String API_MONETIZATION_STATUS = "isMonetizationEnabled";
    public static final String API_MONETIZATION_PROPERTIES = "monetizationProperties";
    public static final String MONETIZATION_INFO = "MonetizationInfo";
    public static final String BILLING_ENGINE_PLATFORM_ACCOUNT_KEY = "BillingEnginePlatformAccountKey";
    public static final String BILLING_ENGINE_CONNECTED_ACCOUNT_KEY = "ConnectedAccountKey";
    public static final String CUSTOMER = "customer";
    public static final String PLAN = "plan";
    public static final String METERED_PLAN = "metered";
    public static final String CUSTOMER_DESCRIPTION = "description";
    public static final String CUSTOMER_EMAIL = "email";
    public static final String CUSTOMER_SOURCE = "source";
    public static final String ITEMS = "items";
    public static final String ACTION = "action";
    public static final String INCREMENT = "increment";
    public static final String TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
    public static final String TIMEZONE_FORMAT = ".023+00:00";
    public static final String QUANTITY = "quantity";
    public static final String TIMESTAMP = "timestamp";
    public static final String TIME_ZONE = "UTC";
    public static final String COMPLETED = "COMPLETED";
    public static final String RUNNING = "RUNNING";
    public static final String INPROGRESS = "INPROGRESS";
    public static final String SUCCESSFULL = "SUCCESSFULL";
    public static final String UNSUCCESSFULL = "UNSUCCESSFULL";
    public static final String FILE_SEPERATOR = "/";
    public static final String DEFAULT_TOKEN = "tok_visa";

    // Key used in WorkflowDTO.attributes to pass the Stripe session ID from the
    // webhook webapp to StripeSubscriptionCreationWorkflowExecutor.complete()
    public static final String CHECKOUT_SESSION_ID_ATTRIBUTE = "checkoutSessionId";

    // Stripe Checkout Session — workflow property keys (stored in AM_WORKFLOWS)
    public static final String CHECKOUT_URL_PROPERTY = "checkoutUrl";
    public static final String CHECKOUT_SESSION_ID_PROPERTY = "checkoutSessionId";

    // Stripe Checkout Session — Stripe metadata keys (attached to the session object)
    public static final String CHECKOUT_METADATA_WORKFLOW_REF = "workflowReference";
    public static final String CHECKOUT_METADATA_SUBSCRIBER_ID = "subscriberId";
    public static final String CHECKOUT_METADATA_TENANT_ID = "tenantId";
    public static final String CHECKOUT_METADATA_API_UUID = "apiUuid";
    // Additional metadata so complete() can reconstruct the subscription context
    // without an extra DB round-trip to getSubscriptionById()
    public static final String CHECKOUT_METADATA_APPLICATION_ID = "applicationId";
    public static final String CHECKOUT_METADATA_TIER_NAME = "tierName";
    public static final String CHECKOUT_METADATA_API_PROVIDER = "apiProvider";
    public static final String CHECKOUT_METADATA_APPLICATION_NAME = "applicationName";
    public static final String CHECKOUT_METADATA_API_NAME = "apiName";
    public static final String CHECKOUT_METADATA_API_VERSION = "apiVersion";

    // Stripe Checkout Session — DB status values
    public static final String CHECKOUT_SESSION_STATUS_PENDING = "PENDING";
    public static final String CHECKOUT_SESSION_STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String CHECKOUT_SESSION_STATUS_COMPLETED = "COMPLETED";
    public static final String CHECKOUT_SESSION_STATUS_EXPIRED = "EXPIRED";

    // Stripe Checkout Session — DB column names
    public static final String CHECKOUT_COL_SESSION_ID = "SESSION_ID";
    public static final String CHECKOUT_COL_WORKFLOW_REF = "WORKFLOW_REFERENCE";
    public static final String CHECKOUT_COL_SUBSCRIBER_ID = "SUBSCRIBER_ID";
    public static final String CHECKOUT_COL_TENANT_ID = "TENANT_ID";
    public static final String CHECKOUT_COL_API_UUID = "API_UUID";
    public static final String CHECKOUT_COL_STATUS = "STATUS";

    // Stripe Checkout Session — SQL statements
    public static final String ADD_CHECKOUT_SESSION_SQL =
            "INSERT INTO AM_STRIPE_CHECKOUT_SESSIONS " +
            "(SESSION_ID, WORKFLOW_REFERENCE, SUBSCRIBER_ID, TENANT_ID, API_UUID, CHECKOUT_URL, STATUS, CREATED_TIME) " +
            "VALUES (?,?,?,?,?,?,?,?)";

    public static final String GET_CHECKOUT_SESSION_BY_SESSION_ID_SQL =
            "SELECT ID, SESSION_ID, WORKFLOW_REFERENCE, SUBSCRIBER_ID, TENANT_ID, API_UUID, STATUS " +
            "FROM AM_STRIPE_CHECKOUT_SESSIONS WHERE SESSION_ID = ?";

    public static final String GET_CHECKOUT_SESSION_BY_WORKFLOW_REF_SQL =
            "SELECT ID, SESSION_ID, WORKFLOW_REFERENCE, SUBSCRIBER_ID, TENANT_ID, API_UUID, STATUS " +
            "FROM AM_STRIPE_CHECKOUT_SESSIONS WHERE WORKFLOW_REFERENCE = ?";

    public static final String UPDATE_CHECKOUT_SESSION_STATUS_SQL =
            "UPDATE AM_STRIPE_CHECKOUT_SESSIONS SET STATUS = ?, COMPLETED_TIME = ? " +
            "WHERE SESSION_ID = ?";

    // Idempotency guard: atomically transition PENDING → IN_PROGRESS so only one
    // completion path (webhook vs browser-redirect) processes the session.
    // Returns rowsAffected = 1 on success, 0 if already claimed/completed.
    public static final String CLAIM_CHECKOUT_SESSION_SQL =
            "UPDATE AM_STRIPE_CHECKOUT_SESSIONS SET STATUS = 'IN_PROGRESS' " +
            "WHERE SESSION_ID = ? AND STATUS = 'PENDING'";

    // Reset IN_PROGRESS → PENDING when completion fails, allowing a retry by the other path.
    public static final String RESET_CHECKOUT_SESSION_CLAIM_SQL =
            "UPDATE AM_STRIPE_CHECKOUT_SESSIONS SET STATUS = 'PENDING' " +
            "WHERE SESSION_ID = ? AND STATUS = 'IN_PROGRESS'";

    public static final String GET_CHECKOUT_URL_BY_WORKFLOW_REF_SQL =
            "SELECT CHECKOUT_URL FROM AM_STRIPE_CHECKOUT_SESSIONS " +
            "WHERE WORKFLOW_REFERENCE = ? AND STATUS = 'PENDING'";
    public static final String INVOICE_NOW = "invoice_now";
    public static final String CANCELED = "canceled";
    public static final String ANALYTICS_ACCESS_TOKEN_PROP = "Monetization.UsagePublisher.AnalyticsAccessToken";
    public static final String CHOREO_INSIGHT_API_ENDPOINT_PROP = "Monetization.UsagePublisher." +
            "ChoreoInsightAPIEndpoint";
    public static final String FROM = "from";
    public static final String TO = "to";
    public static final String API_ID_COL = "apiIds";
    public static final String TENANT_DOMAIN_COL = "tenantDomains";
    public static final String TIME_FILTER = "timeFilter";
    public static final String API_USAGE_BY_APP_FILTER = "successAPIUsageByAppFilter";
    public static final String ON_PREM_KEY = "onPremKey";
    public static final String API_NAME = "apiName";
    public static final String API_UUID = "apiId";
    public static final String API_VERSION = "apiVersion";
    public static final String TENANT_DOMAIN = "apiCreatorTenantDomain";
    public static final String COUNT = "count";
    public static final String APPLICATION_NAME = "applicationName";
    public static final String APPLICATION_OWNER = "applicationOwner";
    public static final String GET_USAGE_BY_APPLICATION = "getSuccessAPIsUsageByApplications";
    public static final String GET_USAGE_BY_APPLICATION_WITH_ON_PREM_KEY = "getSuccessAPIsUsageByApplicationsWithOnPremKey";
    public static final String AT = "@";
    public static final String DEFAULT_ELK_ANALYTICS_INDEX = "apim_event_response";
    public static final String REQUEST_TIMESTAMP_COLUMN = "requestTimestamp";
    public static final String ELK_API_ID_COL = "apiId.keyword";
    public static final String ELK_TENANT_DOMAIN = "apiCreatorTenantDomain.keyword";
    public static final String APPLICATION_ID_COLUMN = "applicationId";
    public static final String ELK_APPLICATION_ID_COLUMN = "applicationId.keyword";

}
