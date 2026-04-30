/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.apim.monetization.impl;

import com.google.gson.Gson;
import com.stripe.exception.StripeException;
import com.stripe.model.billingportal.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.billingportal.SessionCreateParams;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.persistence.APIPersistence;
import org.wso2.carbon.apimgt.persistence.PersistenceManager;
import org.wso2.carbon.apimgt.persistence.dto.Organization;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPI;
import org.wso2.carbon.apimgt.persistence.exceptions.APIPersistenceException;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Creates Stripe Customer Portal sessions for Dev Portal subscribers.
 *
 * <p>The Stripe Customer Portal is a Stripe-hosted page where customers can:
 * <ul>
 *   <li>View and update their payment methods</li>
 *   <li>View invoice history</li>
 *   <li>Manage subscriptions (cancel, update plan)</li>
 * </ul>
 *
 * <p>Access is via a short-lived session URL — the URL itself acts as the
 * authentication token, so no separate Stripe login is required.
 *
 * <p><strong>Important:</strong> In Stripe Connect, subscriptions and invoices are
 * created under the <em>shared customer</em> inside the <em>connected account</em>
 * (not under the platform customer). The portal session must therefore be created
 * with {@code Stripe-Account: <connectedAccountKey>} so that Stripe opens the
 * portal in the correct account context and invoice history is visible.
 *
 * <p>Used by the {@code /api/am/stripe/billing-portal} WAR endpoint.
 */
public class StripeBillingPortalService {

    private static final Log log = LogFactory.getLog(StripeBillingPortalService.class);

    private static StripeBillingPortalService instance;

    private StripeBillingPortalService() {
    }

    public static synchronized StripeBillingPortalService getInstance() {
        if (instance == null) {
            instance = new StripeBillingPortalService();
        }
        return instance;
    }

    /**
     * Creates a Stripe Customer Portal session for the subscriber who owns the given
     * application and returns the short-lived portal URL.
     *
     * <p>Looks up the shared Stripe customer (the one that actually holds subscriptions
     * and invoices in the connected account) and the API's connected account key, then
     * creates the portal session against the connected account so invoice history is shown.
     *
     * @param applicationUUID Dev Portal application UUID
     * @param returnUrl       URL to redirect the user back to after they finish in the portal
     * @return Stripe Customer Portal session URL
     * @throws StripeMonetizationException if the customer is not found, the API key is
     *                                     not configured, or the Stripe API call fails
     */
    public String createPortalSession(String applicationUUID, String returnUrl)
            throws StripeMonetizationException {

        // 1. Look up the Stripe shared customer ID, tenant ID, and an API UUID for this
        //    application. Subscriptions and invoices live on the shared customer in the
        //    connected account — NOT on the platform customer.
        String[] customerInfo = StripeMonetizationDAO.getInstance()
                .getSharedCustomerAndApiByApplicationUUID(applicationUUID);

        if (customerInfo == null) {
            throw new StripeMonetizationException(
                    "No Stripe billing account found for application UUID: " + applicationUUID);
        }

        String sharedCustomerId = customerInfo[0];
        int tenantId = Integer.parseInt(customerInfo[1]);
        String apiUuid = customerInfo[2];

        // 2. Resolve the tenant domain so we can read the correct Stripe API key
        String tenantDomain;
        try {
            tenantDomain = APIUtil.getTenantDomainFromTenantId(tenantId);
        } catch (Exception e) {
            throw new StripeMonetizationException(
                    "Failed to resolve tenant domain for tenant ID: " + tenantId, e);
        }

        // 3. Read the platform Stripe API key from the tenant config
        String platformApiKey = getStripePlatformAccountKey(tenantDomain);

        // 4. Read the ConnectedAccountKey from the API's monetization properties
        //    (stored in the APIM registry — same key used when creating the subscription)
        String connectedAccountKey = getConnectedAccountKeyFromApi(tenantDomain, apiUuid);

        // 5. Create the Stripe Customer Portal session using the shared customer inside
        //    the connected account.  Without setStripeAccount() the portal would open in
        //    the platform account context and show no invoices.
        try {
            RequestOptions requestOptions = RequestOptions.builder()
                    .setApiKey(platformApiKey)
                    .setStripeAccount(connectedAccountKey)
                    .build();

            SessionCreateParams params = SessionCreateParams.builder()
                    .setCustomer(sharedCustomerId)
                    .setReturnUrl(returnUrl)
                    .build();

            Session session = Session.create(params, requestOptions);
            log.info("billing-portal: created Stripe portal session"
                    + " customer=" + sharedCustomerId
                    + " connectedAccount=" + connectedAccountKey
                    + " application=" + applicationUUID);
            return session.getUrl();

        } catch (StripeException e) {
            throw new StripeMonetizationException(
                    "Failed to create Stripe billing portal session for customer: "
                            + sharedCustomerId, e);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Reads the Stripe platform account key from the tenant's monetization configuration.
     * This is the same key used by {@code StripeMonetizationImpl} for all Stripe operations.
     */
    private String getStripePlatformAccountKey(String tenantDomain) throws StripeMonetizationException {
        try {
            JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
            if (tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
                JSONObject monetizationInfo = (JSONObject) tenantConfig
                        .get(StripeMonetizationConstants.MONETIZATION_INFO);
                if (monetizationInfo.containsKey(
                        StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)) {
                    String key = monetizationInfo.get(
                            StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
                    if (StringUtils.isNotBlank(key)) {
                        return key;
                    }
                }
            }
        } catch (APIManagementException e) {
            throw new StripeMonetizationException(
                    "Failed to read Stripe platform account key for tenant: " + tenantDomain, e);
        }
        throw new StripeMonetizationException(
                "Stripe platform account key (BillingEnginePlatformAccountKey) is not configured "
                        + "for tenant: " + tenantDomain);
    }

    /**
     * Reads the {@code ConnectedAccountKey} (Stripe connected account ID, e.g. {@code acct_xxx})
     * from the API's monetization properties stored in the APIM registry.
     *
     * <p>This mirrors exactly what {@code StripeSubscriptionCreationWorkflowExecutor} does
     * when it creates a Stripe subscription — the same connected account must be used when
     * opening the Customer Portal so that the portal shows the correct invoices.
     */
    private String getConnectedAccountKeyFromApi(String tenantDomain, String apiUuid)
            throws StripeMonetizationException {

        Properties properties = new Properties();
        properties.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                APIUtil.isAllowDisplayAPIsWithMultipleStatus());
        properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS,
                APIUtil.isAllowDisplayMultipleVersions());

        Map<String, String> configMap = new HashMap<String, String>();
        Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
        if (configs != null && !configs.isEmpty()) {
            configMap.putAll(configs);
        }
        configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));

        try {
            APIPersistence apiPersistenceInstance =
                    PersistenceManager.getPersistenceInstance(configMap, properties);
            Organization org = new Organization(tenantDomain);
            PublisherAPI publisherAPI = apiPersistenceInstance.getPublisherAPI(org, apiUuid);

            Map<String, String> monetizationProperties = new Gson().fromJson(
                    publisherAPI.getMonetizationProperties().toString(), HashMap.class);

            if (MapUtils.isNotEmpty(monetizationProperties)
                    && monetizationProperties.containsKey(
                            StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                String key = monetizationProperties.get(
                        StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
                if (StringUtils.isNotBlank(key)) {
                    return key;
                }
            }
            throw new StripeMonetizationException(
                    "ConnectedAccountKey not found in monetization properties of API: " + apiUuid);

        } catch (APIPersistenceException e) {
            throw new StripeMonetizationException(
                    "Failed to load API " + apiUuid + " to read ConnectedAccountKey", e);
        }
    }
}
