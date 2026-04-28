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

import com.stripe.exception.StripeException;
import com.stripe.model.billingportal.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.billingportal.SessionCreateParams;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

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
     * @param applicationUUID Dev Portal application UUID
     * @param returnUrl       URL to redirect the user back to after they finish in the portal
     * @return Stripe Customer Portal session URL
     * @throws StripeMonetizationException if the customer is not found, the API key is
     *                                     not configured, or the Stripe API call fails
     */
    public String createPortalSession(String applicationUUID, String returnUrl)
            throws StripeMonetizationException {

        // 1. Look up the Stripe platform customer ID and tenant ID for this application
        String[] customerInfo = StripeMonetizationDAO.getInstance()
                .getStripeCustomerInfoByApplicationUUID(applicationUUID);

        if (customerInfo == null) {
            throw new StripeMonetizationException(
                    "No Stripe billing account found for application UUID: " + applicationUUID);
        }

        String customerId = customerInfo[0];
        int tenantId = Integer.parseInt(customerInfo[1]);

        // 2. Resolve the tenant domain so we can read the correct Stripe API key
        String tenantDomain;
        try {
            tenantDomain = APIUtil.getTenantDomainFromTenantId(tenantId);
        } catch (Exception e) {
            throw new StripeMonetizationException(
                    "Failed to resolve tenant domain for tenant ID: " + tenantId, e);
        }

        // 3. Read the platform Stripe API key from the tenant config
        String apiKey = getStripePlatformAccountKey(tenantDomain);

        // 4. Create the Stripe Customer Portal session
        try {
            RequestOptions requestOptions = RequestOptions.builder()
                    .setApiKey(apiKey)
                    .build();

            SessionCreateParams params = SessionCreateParams.builder()
                    .setCustomer(customerId)
                    .setReturnUrl(returnUrl)
                    .build();

            Session session = Session.create(params, requestOptions);
            log.info("billing-portal: created Stripe portal session for customer=" + customerId
                    + " application=" + applicationUUID);
            return session.getUrl();

        } catch (StripeException e) {
            throw new StripeMonetizationException(
                    "Failed to create Stripe billing portal session for customer: " + customerId, e);
        }
    }

    /**
     * Reads the Stripe platform account key from the tenant's monetization configuration.
     * This is the same key used by {@code StripeMonetizationImpl} for subscription operations.
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
}
