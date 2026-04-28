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

package org.wso2.apim.monetization.webhook.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.jaxrs.ext.MessageContext;
import org.wso2.apim.monetization.impl.StripeBillingPortalService;
import org.wso2.apim.monetization.impl.StripeMonetizationException;

import javax.ws.rs.core.Response;

/**
 * Creates Stripe Customer Portal sessions so Dev Portal users can manage their billing.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Validate the {@code applicationId} query parameter.</li>
 *   <li>Delegate to {@link StripeBillingPortalService#createPortalSession} which:
 *     <ul>
 *       <li>Looks up the Stripe platform customer ID from {@code AM_MONETIZATION_PLATFORM_CUSTOMERS}</li>
 *       <li>Reads the platform Stripe API key from the tenant's monetization config</li>
 *       <li>Calls the Stripe API to create a short-lived billing portal session</li>
 *     </ul>
 *   </li>
 *   <li>Return the portal URL as JSON — the frontend redirects the browser to it.</li>
 * </ol>
 *
 * <h3>Security note</h3>
 * The portal session URL is short-lived (Stripe expires it after a few minutes) and is
 * single-use. No sensitive data is returned; the URL itself acts as the authentication token.
 */
public class BillingPortalApiServiceImpl {

    private static final Log log = LogFactory.getLog(BillingPortalApiServiceImpl.class);

    /**
     * Default return URL used when the caller does not supply one.
     * Returns the user to the Dev Portal home page.
     */
    private static final String DEFAULT_RETURN_URL = "/devportal";

    // -------------------------------------------------------------------------

    public Response getBillingPortalUrl(String applicationId, String returnUrl,
            MessageContext messageContext) {

        if (StringUtils.isBlank(applicationId)) {
            return jsonError(Response.Status.BAD_REQUEST, "applicationId parameter is required");
        }

        String portalReturnUrl = StringUtils.isNotBlank(returnUrl) ? returnUrl : DEFAULT_RETURN_URL;

        try {
            String portalUrl = StripeBillingPortalService.getInstance()
                    .createPortalSession(applicationId, portalReturnUrl);
            return Response.ok("{\"url\":\"" + escapeJson(portalUrl) + "\"}").build();

        } catch (StripeMonetizationException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("No Stripe billing account found")) {
                log.warn("billing-portal: no Stripe customer for application=" + applicationId);
                return jsonError(Response.Status.NOT_FOUND,
                        "No Stripe billing account found for this application. "
                                + "Subscribe to a monetized API first.");
            }
            log.error("billing-portal: failed to create portal session for application="
                    + applicationId, e);
            return jsonError(Response.Status.INTERNAL_SERVER_ERROR,
                    "Failed to create billing portal session — please try again later");
        }
    }

    // -------------------------------------------------------------------------

    private Response jsonError(Response.Status status, String message) {
        return Response.status(status)
                .entity("{\"error\":\"" + escapeJson(message) + "\"}")
                .build();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
