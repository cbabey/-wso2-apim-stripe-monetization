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
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.ws.rs.core.Response;

/**
 * Returns the Stripe Checkout URL for a pending subscription.
 *
 * <p>The DevPortal UI calls {@code GET /api/am/stripe/checkout-url?subscriptionId={id}}
 * immediately after a subscription creation returns {@code ON_HOLD}. This service
 * queries {@code AM_STRIPE_CHECKOUT_SESSIONS} for the stored URL and returns it so
 * the UI can redirect the subscriber to the Stripe-hosted payment page.
 */
public class CheckoutUrlApiServiceImpl {

    private static final Log log = LogFactory.getLog(CheckoutUrlApiServiceImpl.class);

    /** Column name in AM_STRIPE_CHECKOUT_SESSIONS */
    private static final String COL_CHECKOUT_URL = "CHECKOUT_URL";

    /**
     * SQL to retrieve the checkout URL for a pending session by APIM subscription UUID.
     *
     * <p>AM_STRIPE_CHECKOUT_SESSIONS.WORKFLOW_REFERENCE stores the numeric SUBSCRIPTION_ID
     * as a string, so we join AM_SUBSCRIPTION on CAST(SUBSCRIPTION_ID AS CHAR) to look up
     * by the subscription UUID that the Dev Portal API exposes.
     */
    private static final String GET_CHECKOUT_URL_BY_SUB_UUID_SQL =
            "SELECT cs.CHECKOUT_URL " +
            "FROM AM_STRIPE_CHECKOUT_SESSIONS cs " +
            "JOIN AM_SUBSCRIPTION sub " +
            "  ON cs.WORKFLOW_REFERENCE = CAST(sub.SUBSCRIPTION_ID AS CHAR) " +
            "WHERE sub.UUID = ? " +
            "  AND cs.STATUS = 'PENDING'";

    // -------------------------------------------------------------------------

    public Response getCheckoutUrl(String subscriptionId, MessageContext messageContext) {

        if (StringUtils.isBlank(subscriptionId)) {
            return jsonResponse(Response.Status.BAD_REQUEST, "subscriptionId query parameter is required");
        }

        String checkoutUrl = queryCheckoutUrl(subscriptionId);

        if (checkoutUrl == null) {
            // No pending Stripe session for this subscription — not a Stripe-monetized API,
            // or the session was already completed/expired.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"No pending Stripe checkout session found\"}")
                    .build();
        }

        String body = "{\"checkoutUrl\":\"" + escapeJson(checkoutUrl) + "\"}";
        return Response.ok(body).build();
    }

    // -------------------------------------------------------------------------

    private String queryCheckoutUrl(String subscriptionId) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(GET_CHECKOUT_URL_BY_SUB_UUID_SQL);
            ps.setString(1, subscriptionId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString(COL_CHECKOUT_URL);
            }
            return null;
        } catch (SQLException e) {
            log.error("Failed to retrieve checkout URL for subscription UUID: " + subscriptionId, e);
            return null;
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
    }

    private Response jsonResponse(Response.Status status, String message) {
        return Response.status(status)
                .entity("{\"error\":\"" + escapeJson(message) + "\"}")
                .build();
    }

    /** Minimal JSON string escaping — only needed for values we control, but safe practice. */
    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
