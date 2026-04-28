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
 * Returns the Stripe Checkout session details for a pending subscription.
 *
 * <p>The DevPortal UI calls {@code GET /api/am/stripe/checkout-url?subscriptionId={uuid}}
 * when it detects an ON_HOLD monetized subscription. The response includes both the
 * {@code checkoutUrl} (to redirect the user if payment is genuinely incomplete) and the
 * {@code sessionId} (so the frontend can first attempt to re-trigger activation via
 * {@code /complete-session} in case the payment already went through but the webhook
 * or activation step failed).
 */
public class CheckoutUrlApiServiceImpl {

    private static final Log log = LogFactory.getLog(CheckoutUrlApiServiceImpl.class);

    /**
     * SQL to retrieve the checkout URL and Stripe session ID for a pending session
     * by APIM subscription UUID.
     *
     * <p>{@code AM_STRIPE_CHECKOUT_SESSIONS.WORKFLOW_REFERENCE} stores the numeric
     * {@code SUBSCRIPTION_ID} as a string, so we join {@code AM_SUBSCRIPTION} on
     * {@code CAST(SUBSCRIPTION_ID AS CHAR)} to look up by the UUID that the
     * Dev Portal REST API exposes.
     *
     * <p>We also return rows whose STATUS is {@code IN_PROGRESS} — this covers the
     * case where the webhook claimed the session (PENDING→IN_PROGRESS) but the
     * activation failed and the reset back to PENDING didn't happen (e.g. server
     * crash mid-flight).
     */
    private static final String GET_CHECKOUT_SESSION_BY_SUB_UUID_SQL =
            "SELECT cs.CHECKOUT_URL, cs.SESSION_ID " +
            "FROM AM_STRIPE_CHECKOUT_SESSIONS cs " +
            "JOIN AM_SUBSCRIPTION sub " +
            "  ON cs.WORKFLOW_REFERENCE = CAST(sub.SUBSCRIPTION_ID AS CHAR) " +
            "WHERE sub.UUID = ? " +
            "  AND cs.STATUS IN ('PENDING', 'IN_PROGRESS')";

    // -------------------------------------------------------------------------

    public Response getCheckoutUrl(String subscriptionId, MessageContext messageContext) {

        if (StringUtils.isBlank(subscriptionId)) {
            return jsonResponse(Response.Status.BAD_REQUEST,
                    "subscriptionId query parameter is required");
        }

        String[] session = queryCheckoutSession(subscriptionId);

        if (session == null) {
            // No pending/in-progress Stripe session — either the subscription is not
            // Stripe-monetized, or the session was already successfully completed.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"No pending Stripe checkout session found\"}")
                    .build();
        }

        String checkoutUrl = session[0];
        String sessionId   = session[1];

        // Return both the redirect URL and the session ID.
        // The frontend tries complete-session first (handles the case where payment
        // already went through but activation failed); only falls back to redirecting
        // to checkoutUrl if the Stripe session is still open and unpaid.
        String body = "{\"checkoutUrl\":\"" + escapeJson(checkoutUrl) + "\","
                    + "\"sessionId\":\"" + escapeJson(sessionId) + "\"}";
        return Response.ok(body).build();
    }

    // -------------------------------------------------------------------------

    private String[] queryCheckoutSession(String subscriptionUuid) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(GET_CHECKOUT_SESSION_BY_SUB_UUID_SQL);
            ps.setString(1, subscriptionUuid);
            rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{
                        rs.getString("CHECKOUT_URL"),
                        rs.getString("SESSION_ID")
                };
            }
            return null;
        } catch (SQLException e) {
            log.error("Failed to retrieve checkout session for subscription UUID: "
                    + subscriptionUuid, e);
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

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
