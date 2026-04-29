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

import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Handles the browser-redirect completion path for Stripe Checkout.
 *
 * <p>After a user completes payment on Stripe, the browser is redirected to
 * {@code {successUrl}/{appUUID}/subscriptions?session_id=cs_xxx}. The DevPortal UI
 * detects the {@code session_id} query parameter and calls
 * {@code POST /api/am/stripe/complete-session?session_id=cs_xxx} to activate
 * the APIM subscription immediately — before the Stripe webhook fires.
 *
 * <p>The concurrent webhook path ({@code checkout.session.completed}) and this
 * browser-redirect path both call
 * {@code StripeSubscriptionCreationWorkflowExecutor.complete()}. A DB-level
 * idempotency guard (PENDING → IN_PROGRESS) in the executor ensures exactly one
 * path processes each session.
 */
public class CompleteSessionApiServiceImpl {

    private static final Log log = LogFactory.getLog(CompleteSessionApiServiceImpl.class);

    /**
     * Attribute key passed to {@code WorkflowExecutor.complete()} so the executor
     * knows to run the Checkout-based subscription activation path.
     * Must match {@code StripeMonetizationConstants.CHECKOUT_SESSION_ID_ATTRIBUTE}
     * in the stripe-plugin bundle.
     */
    private static final String ATTR_CHECKOUT_SESSION_ID = "checkoutSessionId";

    /**
     * Retrieves the workflow reference and current session status for a given Stripe
     * Checkout Session ID from {@code AM_STRIPE_CHECKOUT_SESSIONS}.
     */
    private static final String GET_WORKFLOW_REF_BY_SESSION_SQL =
            "SELECT WORKFLOW_REFERENCE, STATUS FROM AM_STRIPE_CHECKOUT_SESSIONS WHERE SESSION_ID = ?";

    // -------------------------------------------------------------------------

    /**
     * Completes the pending APIM subscription workflow for the given Stripe session.
     *
     * <p>Steps:
     * <ol>
     *   <li>Validate the {@code session_id} parameter.</li>
     *   <li>Look up the APIM workflow reference and current status from the local DB.</li>
     *   <li>Return 200 immediately if the session is already {@code COMPLETED} (idempotent).</li>
     *   <li>Load the pending {@code WorkflowDTO} from the APIM DB.</li>
     *   <li>Set {@code checkoutSessionId} in the workflow attributes and call
     *       {@code WorkflowExecutor.complete()} — which delegates to
     *       {@code StripeSubscriptionCreationWorkflowExecutor.complete()} at runtime.</li>
     * </ol>
     *
     * @param sessionId      Stripe Checkout Session ID ({@code cs_xxx})
     * @param messageContext CXF message context (unused; present for symmetry with other impls)
     * @return HTTP 200 on success, 4xx/5xx on failure
     */
    public Response completeSession(String sessionId, MessageContext messageContext) {

        if (StringUtils.isBlank(sessionId)) {
            return jsonResponse(Response.Status.BAD_REQUEST, "session_id query parameter is required");
        }

        // 1. Look up workflow reference and status from the local DB
        String workflowReference = null;
        String sessionStatus = null;

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(GET_WORKFLOW_REF_BY_SESSION_SQL);
            ps.setString(1, sessionId);
            rs = ps.executeQuery();
            if (rs.next()) {
                workflowReference = rs.getString("WORKFLOW_REFERENCE");
                sessionStatus = rs.getString("STATUS");
            }
        } catch (SQLException e) {
            log.error("DB error looking up checkout session: " + sessionId, e);
            return jsonResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Database error while looking up checkout session");
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }

        if (StringUtils.isBlank(workflowReference)) {
            log.warn("complete-session: no checkout session found for session_id: " + sessionId);
            return jsonResponse(Response.Status.NOT_FOUND, "Checkout session not found");
        }

        // 2. Already COMPLETED — return success without doing any more work (idempotent)
        if ("COMPLETED".equals(sessionStatus)) {
            log.info("complete-session: session already COMPLETED, nothing to do: " + sessionId);
            return Response.ok("{\"status\":\"already_completed\"}").build();
        }

        // 3. Load the pending WorkflowDTO from the APIM DB
        WorkflowDTO workflowDTO;
        try {
            workflowDTO = ApiMgtDAO.getInstance().retrieveWorkflowFromInternalReference(
                    workflowReference, WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);
        } catch (APIManagementException e) {
            log.error("complete-session: APIM error retrieving workflow for workflowRef="
                    + workflowReference + " session=" + sessionId, e);
            return jsonResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Failed to retrieve workflow");
        }

        if (workflowDTO == null) {
            log.warn("complete-session: no workflow found for workflowReference=" + workflowReference
                    + " (session=" + sessionId + ")");
            return jsonResponse(Response.Status.NOT_FOUND, "Workflow not found for this session");
        }

        // 4. Pass the Stripe session ID via the attributes map.
        //    StripeSubscriptionCreationWorkflowExecutor.complete() reads this key to run
        //    the Checkout-based activation path (with idempotency guard).
        workflowDTO.getAttributes().put(ATTR_CHECKOUT_SESSION_ID, sessionId);
        workflowDTO.setStatus(WorkflowStatus.APPROVED);
        workflowDTO.setUpdatedTime(System.currentTimeMillis());

        try {
            WorkflowExecutor executor = WorkflowExecutorFactory.getInstance()
                    .getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);
            executor.complete(workflowDTO);
        } catch (WorkflowException e) {
            log.error("complete-session: workflow execution error for session=" + sessionId, e);
            return jsonResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Subscription activation failed: " + e.getMessage());
        }

        log.info("complete-session: subscription activated via browser-redirect path "
                + "session=" + sessionId + " workflowRef=" + workflowReference);
        return Response.ok("{\"status\":\"completed\"}").build();
    }

    // -------------------------------------------------------------------------

    private Response jsonResponse(Response.Status status, String message) {
        return Response.status(status)
                .entity("{\"error\":\"" + escapeJson(message) + "\"}")
                .build();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
