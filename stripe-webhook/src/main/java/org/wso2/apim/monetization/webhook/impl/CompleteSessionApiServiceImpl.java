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
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowConstants;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutorFactory;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.ws.rs.core.Response;

/**
 * Handles browser-redirect Stripe subscription completion.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Look up the Stripe Checkout Session in {@code AM_STRIPE_CHECKOUT_SESSIONS} to
 *       obtain the APIM {@code WORKFLOW_REFERENCE} and current {@code STATUS}.</li>
 *   <li>If STATUS is {@code COMPLETED}, return 200 immediately — the subscription is
 *       already active (webhook beat us to it).</li>
 *   <li>Load the pending {@link WorkflowDTO} from APIM DB via
 *       {@code ApiMgtDAO.retrieveWorkflowFromInternalReference()}.</li>
 *   <li>Set {@code checkoutSessionId} in the DTO attributes and APPROVED status, then
 *       delegate to {@code WorkflowExecutorFactory.complete()} — at runtime this dispatches
 *       to {@code StripeSubscriptionCreationWorkflowExecutor.complete()}, which applies an
 *       atomic DB claim (PENDING → IN_PROGRESS) as an idempotency guard so that whichever
 *       path (this endpoint or the Stripe webhook) wins processes the session, and the other
 *       safely skips the Stripe API calls.</li>
 * </ol>
 */
public class CompleteSessionApiServiceImpl {

    private static final Log log = LogFactory.getLog(CompleteSessionApiServiceImpl.class);

    /**
     * Key passed via WorkflowDTO.attributes to StripeSubscriptionCreationWorkflowExecutor.complete()
     * so it knows to run the Checkout-based customer + subscription creation path.
     * Must match WebhookApiServiceImpl.ATTR_CHECKOUT_SESSION_ID.
     */
    private static final String ATTR_CHECKOUT_SESSION_ID = "checkoutSessionId";

    private static final String COL_WORKFLOW_REFERENCE = "WORKFLOW_REFERENCE";
    private static final String COL_STATUS = "STATUS";
    private static final String STATUS_COMPLETED   = "COMPLETED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

    /**
     * Query to look up a checkout session by Stripe session ID.
     * Intentionally returns any non-expired row regardless of status so this endpoint
     * can respond with "already completed" when the webhook wins.
     */
    private static final String GET_SESSION_SQL =
            "SELECT WORKFLOW_REFERENCE, STATUS FROM AM_STRIPE_CHECKOUT_SESSIONS WHERE SESSION_ID = ?";

    // -------------------------------------------------------------------------

    public Response completeSession(String sessionId, MessageContext messageContext) {

        // 1. Validate input
        if (StringUtils.isBlank(sessionId)) {
            return jsonError(Response.Status.BAD_REQUEST, "session_id query parameter is required");
        }
        if (!sessionId.startsWith("cs_")) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid session_id format");
        }

        // 2. Look up the checkout session in the APIM DB
        String[] sessionData = querySession(sessionId);
        if (sessionData == null) {
            log.warn("complete-session: no checkout session found for session_id: " + sessionId);
            return jsonError(Response.Status.NOT_FOUND, "Checkout session not found");
        }

        String workflowReference = sessionData[0];
        String status = sessionData[1];

        // 3. If already completed (webhook won the race), just tell the UI to refresh
        if (STATUS_COMPLETED.equals(status)) {
            log.info("complete-session: session " + sessionId + " already completed — returning OK");
            return Response.ok("{\"status\":\"already_completed\"}").build();
        }

        // 4. Load the pending APIM workflow
        try {
            ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
            WorkflowDTO workflowDTO = apiMgtDAO.retrieveWorkflowFromInternalReference(
                    workflowReference,
                    WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);

            if (workflowDTO == null) {
                // Workflow not found in PENDING state — it may have been completed by the webhook
                // after we read the session status but before this lookup. Treat as success.
                log.info("complete-session: no pending workflow for session " + sessionId
                        + " (workflowRef=" + workflowReference + ") — may have been completed concurrently");
                return Response.ok("{\"status\":\"already_completed\"}").build();
            }

            // 5. Set the checkout session ID so the executor knows to run the Checkout path
            workflowDTO.getAttributes().put(ATTR_CHECKOUT_SESSION_ID, sessionId);
            workflowDTO.setStatus(WorkflowStatus.APPROVED);
            workflowDTO.setUpdatedTime(System.currentTimeMillis());

            // 6. Invoke the workflow executor (dispatches to StripeSubscriptionCreationWorkflowExecutor)
            WorkflowExecutor executor = WorkflowExecutorFactory.getInstance()
                    .getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);
            executor.complete(workflowDTO);

            log.info("complete-session: subscription activated for session=" + sessionId
                    + " workflowRef=" + workflowReference);
            return Response.ok("{\"status\":\"completed\"}").build();

        } catch (APIManagementException e) {
            log.error("complete-session: APIM error completing session: " + sessionId, e);
            return jsonError(Response.Status.INTERNAL_SERVER_ERROR,
                    "Failed to activate subscription — please contact support");
        } catch (WorkflowException e) {
            // Stripe declined the initial subscription payment.  The subscription ID was saved
            // to the DB so Fix 3 (customer.subscription.updated) activates the APIM subscription
            // when Stripe retries and collects payment.  Return 402 so the UI shows a
            // payment-declined message instead of redirecting to the already-consumed checkout URL.
            if (e.getMessage() != null && e.getMessage().startsWith("STRIPE_PAYMENT_DECLINED:")) {
                log.warn("complete-session: Stripe payment declined for session=" + sessionId
                        + " — subscription saved, awaiting Stripe automatic retry");
                return Response.status(Response.Status.PAYMENT_REQUIRED)
                        .entity("{\"error\":\"payment_declined\","
                                + "\"message\":\"Your initial payment was declined."
                                + " Stripe will retry automatically."
                                + " You can also delete this subscription and re-subscribe"
                                + " with a different card.\"}")
                        .build();
            }
            // The executor throws WorkflowException when the idempotency guard prevents a
            // double-completion. Re-query the actual DB status to tell the difference between
            // a real failure and a benign "webhook already claimed it" race condition.
            String[] refreshed = querySession(sessionId);
            String refreshedStatus = refreshed != null ? refreshed[1] : null;
            if (STATUS_COMPLETED.equals(refreshedStatus)) {
                log.info("complete-session: session " + sessionId
                        + " was completed concurrently by the webhook — returning OK");
                return Response.ok("{\"status\":\"already_completed\"}").build();
            } else if (STATUS_IN_PROGRESS.equals(refreshedStatus)) {
                // Webhook has claimed it and is currently processing — not an error.
                // The subscription will be activated within seconds; tell the UI to keep polling.
                log.info("complete-session: session " + sessionId
                        + " is being processed by the webhook path — returning accepted");
                return Response.accepted("{\"status\":\"being_activated\"}").build();
            }
            log.error("complete-session: workflow error completing session: " + sessionId, e);
            return jsonError(Response.Status.INTERNAL_SERVER_ERROR,
                    "Failed to activate subscription — please contact support");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Queries the checkout session row.
     *
     * @return String[]{workflowReference, status} or {@code null} if not found
     */
    private String[] querySession(String sessionId) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(GET_SESSION_SQL);
            ps.setString(1, sessionId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{
                        rs.getString(COL_WORKFLOW_REFERENCE),
                        rs.getString(COL_STATUS)
                };
            }
            return null;
        } catch (SQLException e) {
            log.error("complete-session: DB error querying session: " + sessionId, e);
            return null;
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
    }

    private Response jsonError(Response.Status status, String message) {
        return Response.status(status)
                .entity("{\"error\":\"" + escapeJson(message) + "\"}")
                .build();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
