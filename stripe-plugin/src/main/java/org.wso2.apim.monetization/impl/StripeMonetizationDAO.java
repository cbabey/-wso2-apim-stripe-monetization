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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.apim.monetization.impl.model.MonetizationPlatformCustomer;
import org.wso2.apim.monetization.impl.model.MonetizationSharedCustomer;
import org.wso2.apim.monetization.impl.model.MonetizedSubscription;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is used to handle database related actions when configuring monetization with stripe
 */
public class StripeMonetizationDAO {

    private static final Log log = LogFactory.getLog(StripeMonetizationDAO.class);
    private ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
    private static StripeMonetizationDAO INSTANCE = null;

    /**
     * Method to get the instance of the StripeMonetizationDAO.
     *
     * @return {@link StripeMonetizationDAO} instance
     */
    public static StripeMonetizationDAO getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new StripeMonetizationDAO();
        }
        return INSTANCE;
    }

    /**
     * Add monetization plan data to the database
     *
     * @param policy    subscription policy
     * @param productId product id (in the billing engine)
     * @param planId    plan id (in the billing engine)
     * @throws StripeMonetizationException if failed to add monetization plan data to the database
     */
    public void addMonetizationPlanData(SubscriptionPolicy policy, String productId, String planId)
            throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(StripeMonetizationConstants.INSERT_MONETIZATION_PLAN_DATA_SQL);
            policyStatement.setString(1, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(),
                    policy.getTenantId()).getUUID());
            policyStatement.setString(2, productId);
            policyStatement.setString(3, planId);
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback adding monetization plan for : " + policy.getPolicyName();
                    log.error(errorMessage);
                    throw new StripeMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to add monetization plan for : " + policy.getPolicyName();
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscription policy : " + policy.getPolicyName() +
                    " from database when creating stripe plan.";
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Get plan data (in billing engine) for a given subscription policy
     *
     * @param policy subscription policy
     * @return plan data of subscription policy
     * @throws StripeMonetizationException if failed to get plan data
     */
    public Map<String, String> getPlanData(SubscriptionPolicy policy) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        Map<String, String> planData = new HashMap<String, String>();
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(StripeMonetizationConstants.GET_BILLING_PLAN_DATA);
            ps.setString(1, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(), policy.getTenantId()).getUUID());
            rs = ps.executeQuery();
            while (rs.next()) {
                planData.put(StripeMonetizationConstants.PRODUCT_ID, rs.getString("PRODUCT_ID"));
                planData.put(StripeMonetizationConstants.PLAN_ID, rs.getString("PLAN_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Error while getting plan data for : " + policy.getPolicyName() + " policy.";
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscription policy : " + policy.getPolicyName() +
                    " when getting plan data.";
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return planData;
    }

    /**
     * Update monetization plan data in the database
     *
     * @param policy    subscription policy
     * @param productId product id (in the billing engine)
     * @param planId    plan id (in the billing engine)
     * @throws StripeMonetizationException if failed to update monetization plan data to the database
     */
    public void updateMonetizationPlanData(SubscriptionPolicy policy, String productId, String planId)
            throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(StripeMonetizationConstants.UPDATE_MONETIZATION_PLAN_ID_SQL);
            policyStatement.setString(1, planId);
            policyStatement.setString(2, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(),
                    policy.getTenantId()).getUUID());
            policyStatement.setString(3, productId);
            policyStatement.execute();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback the update monetization plan action for policy : " +
                            policy.getPolicyName();
                    log.error(errorMessage);
                    throw new StripeMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to update monetization plan for policy: " + policy;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get subscription policy : " + policy.getPolicyName() +
                    " when updating monetization plan data.";
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);

        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * Delete monetization plan data from the database
     *
     * @param policy subscription policy
     * @throws StripeMonetizationException if failed to delete monetization plan data from the database
     */
    public void deleteMonetizationPlanData(SubscriptionPolicy policy) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement policyStatement = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            policyStatement = conn.prepareStatement(StripeMonetizationConstants.DELETE_MONETIZATION_PLAN_DATA);
            policyStatement.setString(1, apiMgtDAO.getSubscriptionPolicy(policy.getPolicyName(),
                    policy.getTenantId()).getUUID());
            policyStatement.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    String errorMessage = "Failed to rollback the delete monetization plan action for policy : " +
                            policy.getPolicyName();
                    log.error(errorMessage);
                    throw new StripeMonetizationException(errorMessage, ex);
                }
            }
            String errorMessage = "Failed to delete the monetization plan action for policy : " + policy.getPolicyName();
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to get policy : " + policy.getPolicyName() +
                    " when deleting monetization plan.";
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(policyStatement, conn, null);
        }
    }

    /**
     * This method is used to get the product id in the billing engine for a give API
     *
     * @param apiId API ID
     * @return billing engine product ID of the give API
     */
    public String getBillingEngineProductId(int apiId) throws StripeMonetizationException {

        String billingEngineProductId = null;
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(StripeMonetizationConstants.GET_BILLING_ENGINE_PRODUCT_BY_API);
            statement.setInt(1, apiId);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                billingEngineProductId = rs.getString("STRIPE_PRODUCT_ID");
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine product ID of API : " + apiId;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return billingEngineProductId;
    }

    /**
     * Get billing plan ID for a given tier
     *
     * @param apiID    API ID
     * @param tierName tier name
     * @return billing plan ID for a given tier
     * @throws StripeMonetizationException if failed to get billing plan ID for a given tier
     */
    public String getBillingEnginePlanIdForTier(int apiID, String tierName) throws StripeMonetizationException {

        Connection connection = null;
        PreparedStatement statement = null;
        String billingEnginePlanId = StringUtils.EMPTY;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(StripeMonetizationConstants.GET_BILLING_PLAN_FOR_TIER);
            statement.setInt(1, apiID);
            statement.setString(2, tierName);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                billingEnginePlanId = rs.getString("STRIPE_PLAN_ID");
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing plan ID tier : " + tierName;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return billingEnginePlanId;
    }

    /**
     * This method is used to add monetization data to the DB
     *
     * @param apiId       API ID
     * @param productId   stripe product ID
     * @param tierPlanMap stripe plan and tier mapping
     * @throws StripeMonetizationException if failed to add monetization data to the DB
     */
    public void addMonetizationData(int apiId, String productId, Map<String, String> tierPlanMap)
            throws StripeMonetizationException {

        PreparedStatement preparedStatement = null;
        Connection connection = null;
        boolean initialAutoCommit = false;
        try {
            if (!tierPlanMap.isEmpty()) {
                connection = APIMgtDBUtil.getConnection();
                preparedStatement = connection.prepareStatement(StripeMonetizationConstants.ADD_MONETIZATION_DATA_SQL);
                initialAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                for (Map.Entry<String, String> entry : tierPlanMap.entrySet()) {
                    preparedStatement.setInt(1, apiId);
                    preparedStatement.setString(2, entry.getKey());
                    preparedStatement.setString(3, productId);
                    preparedStatement.setString(4, entry.getValue());
                    preparedStatement.addBatch();
                }
                preparedStatement.executeBatch();
                connection.commit();
            }
        } catch (SQLException e) {
            try {
                if (connection != null) {
                    connection.rollback();
                }
            } catch (SQLException ex) {
                String errorMessage = "Failed to rollback add monetization data for API : " + apiId;
                log.error(errorMessage, e);
                throw new StripeMonetizationException(errorMessage, e);
            } finally {
                APIMgtDBUtil.setAutoCommit(connection, initialAutoCommit);
            }
        } finally {
            APIMgtDBUtil.closeAllConnections(preparedStatement, connection, null);
        }
    }

    /**
     * Get billing plan ID for a given tier
     *
     * @param tierUUID tier UUID
     * @return billing plan ID for a given tier
     * @throws StripeMonetizationException if failed to get billing plan ID for a given tier
     */
    public String getBillingPlanId(String tierUUID) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String planId = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(StripeMonetizationConstants.GET_BILLING_PLAN_ID);
            ps.setString(1, tierUUID);
            rs = ps.executeQuery();
            while (rs.next()) {
                planId = rs.getString("PLAN_ID");
            }
        } catch (SQLException e) {
            String errorMessage = "Error while getting stripe plan ID for tier UUID : " + tierUUID;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return planId;
    }

    /**
     * Get subscription UUID given the subscription ID
     *
     * @param subscriptionId subscription ID
     * @return subscription UUID
     * @throws StripeMonetizationException if failed to get subscription UUID given the subscription ID
     */
    public String getSubscriptionUUID(int subscriptionId) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String planId = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(StripeMonetizationConstants.GET_SUBSCRIPTION_UUID);
            ps.setInt(1, subscriptionId);
            rs = ps.executeQuery();
            while (rs.next()) {
                planId = rs.getString("UUID");
            }
        } catch (SQLException e) {
            String errorMessage = "Error while getting UUID of subscription ID : " + subscriptionId;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return planId;
    }

    /**
     * This method is used to get stripe plan and tier mapping
     *
     * @param apiID           API ID
     * @param stripeProductId stripe product ID
     * @return mapping between tier and stripe plans
     * @throws StripeMonetizationException if failed to get mapping between tier and stripe plans
     */
    public Map<String, String> getTierToBillingEnginePlanMapping(int apiID, String stripeProductId)
            throws StripeMonetizationException {

        Map<String, String> stripePlanTierMap = new HashMap<String, String>();
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(StripeMonetizationConstants.GET_BILLING_PLANS_BY_PRODUCT);
            statement.setInt(1, apiID);
            statement.setString(2, stripeProductId);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                String tierName = rs.getString("TIER_NAME");
                String stripePlanId = rs.getString("STRIPE_PLAN_ID");
                stripePlanTierMap.put(tierName, stripePlanId);
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get stripe plan and tier mapping for API : " + apiID;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return stripePlanTierMap;
    }

    /**
     * This method deletes monetization data for a given API from the DB
     *
     * @param apiId API ID
     * @throws StripeMonetizationException if failed to delete monetization data
     */
    public void deleteMonetizationData(int apiId) throws StripeMonetizationException {

        Connection connection = null;
        PreparedStatement statement = null;
        boolean initialAutoCommit = false;
        try {
            connection = APIMgtDBUtil.getConnection();
            statement = connection.prepareStatement(StripeMonetizationConstants.DELETE_MONETIZATION_DATA_SQL);
            initialAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            statement.setInt(1, apiId);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            try {
                if (connection != null) {
                    connection.rollback();
                }
            } catch (SQLException ex) {
                String errorMessage = "Failed to delete monetization data for API : " + apiId;
                log.error(errorMessage);
                throw new StripeMonetizationException(errorMessage, e);
            } finally {
                APIMgtDBUtil.setAutoCommit(connection, initialAutoCommit);
            }
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
    }

    /**
     * Get Billing Engine Subscription ID
     *
     * @param apiId         API ID
     * @param applicationId Application ID
     * @return Billing Engine Subscription ID
     * @throws StripeMonetizationException If Failed To Get Billing Engine Subscription ID
     */
    public String getBillingEngineSubscriptionId(int apiId, int applicationId) throws StripeMonetizationException {

        String billingEngineSubscriptionId = null;
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(StripeMonetizationConstants.GET_BILLING_ENGINE_SUBSCRIPTION_ID);
            statement.setInt(1, applicationId);
            statement.setInt(2, apiId);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                billingEngineSubscriptionId = rs.getString("SUBSCRIPTION_ID");
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine subscription ID of API : " + apiId +
                    " and application ID : " + applicationId;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(statement, connection, null);
        }
        return billingEngineSubscriptionId;
    }

    /**
     * Add billing engine platform customers info
     *
     * @param subscriberId Subscriber's Id
     * @param tenantId     Id of tenant
     * @param customerId   Id of the customer created in stripe
     * @return Id of the customer record in the database
     * @throws StripeMonetizationException If failed to add billing engine customer details
     */
    public int addBEPlatformCustomer(int subscriberId, int tenantId, String customerId) throws
            StripeMonetizationException {

        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement ps = null;
        int id = 0;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            String query = StripeMonetizationConstants.ADD_BE_PLATFORM_CUSTOMER_SQL;
            ps = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, subscriberId);
            ps.setInt(2, tenantId);
            ps.setString(3, customerId);
            ps.executeUpdate();
            ResultSet set = ps.getGeneratedKeys();
            if (set.next()) {
                id = set.getInt(1);
            } else {
                String errorMessage = "Failed to get ID of the monetized subscription. Subscriber ID : " +
                        subscriberId + " , tenant ID : " + tenantId + " , customer ID : " + customerId;
                throw new StripeMonetizationException(errorMessage);
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed operation", ex);
                }
            }
            String errorMessage = "Failed to add Stripe platform customer details for Subscriber : " + subscriberId;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return id;
    }

    /**
     * Add Billing Engine Shared Customer info
     *
     * @param sharedCustomer object with Billing Engine Shared customer info
     * @return Id of the customer record in the database
     * @throws StripeMonetizationException If Failed To add Billing Engine Shared Customer details
     */
    public int addBESharedCustomer(MonetizationSharedCustomer sharedCustomer) throws StripeMonetizationException {

        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement ps = null;
        int id = 0;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            String query = StripeMonetizationConstants.ADD_BE_SHARED_CUSTOMER_SQL;
            ps = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, sharedCustomer.getApplicationId());
            ps.setString(2, sharedCustomer.getApiProvider());
            ps.setInt(3, sharedCustomer.getTenantId());
            ps.setString(4, sharedCustomer.getSharedCustomerId());
            ps.setInt(5, sharedCustomer.getParentCustomerId());
            ps.executeUpdate();
            ResultSet set = ps.getGeneratedKeys();
            if (set.next()) {
                id = set.getInt(1);
            } else {
                String errorMessage = "Failed to set ID of the shared customer : " + sharedCustomer.getId() +
                        " , tenant ID : " + sharedCustomer.getTenantId() + " , application ID : " +
                        sharedCustomer.getApplicationId();
                throw new StripeMonetizationException(errorMessage);
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed operation", ex);
                }
            }
            String errorMessage = "Failed to add info of billing engine shared customer created"
                    + " for Application with ID :" + sharedCustomer.getApplicationId()
                    + " under Provider : " + sharedCustomer.getApiProvider();
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return id;
    }

    /**
     * Create Billing Engine Subscription Info
     *
     * @param identifier       API identifier
     * @param applicationId    Id of the Application
     * @param tenandId         Id of the tenant
     * @param sharedCustomerId Id of the shared customer
     * @param subscriptionId   Id of the Billing Engine Subscriptions
     * @param apiUuid          UUID of the API
     * @return Id of the customer record in the database
     * @throws StripeMonetizationException If Failed To add Billing Engine Shared Customer details
     */
    public void addBESubscription(APIIdentifier identifier, int applicationId, int tenandId, int sharedCustomerId,
            String subscriptionId, String apiUuid) throws StripeMonetizationException {

        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement ps = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            String query = StripeMonetizationConstants.ADD_BE_SUBSCRIPTION_SQL;
            ps = conn.prepareStatement(query);
            ps.setString(1, apiUuid);
            ps.setInt(2, applicationId);
            ps.setInt(3, tenandId);
            ps.setInt(4, sharedCustomerId);
            ps.setString(5, subscriptionId);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed operation", ex);
                }
            }
            String errorMessage = "Failed to add Stripe subscription info for API : " + identifier.getApiName() + " by"
                    + " Application : " + applicationId;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
    }

    /**
     * Get Billing Engine Platform Customer Info
     *
     * @param subscriberId Id of the Subscriber
     * @param tenantId     Id of the tenant
     * @return MonetizationPlatformCustomer info of Billing Engine Platform Customer
     * @throws StripeMonetizationException If Failed To get Billing Engine Platform Customer details
     */
    public MonetizationPlatformCustomer getPlatformCustomer(int subscriberId, int tenantId) throws
            StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        MonetizationPlatformCustomer monetizationPlatformCustomer = new MonetizationPlatformCustomer();
        String sqlQuery = StripeMonetizationConstants.GET_BE_PLATFORM_CUSTOMER_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, subscriberId);
            ps.setInt(2, tenantId);
            result = ps.executeQuery();
            if (result.next()) {
                monetizationPlatformCustomer.setId(result.getInt("ID"));
                monetizationPlatformCustomer.setCustomerId(result.getString("CUSTOMER_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine platform customer details for Subscriber : " +
                    subscriberId;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
        return monetizationPlatformCustomer;
    }

    /**
     * Get Billing Engine Shared Customer Info
     *
     * @param applicationId Id of the Application
     * @param apiProvider   api provider
     * @param tenantId      Id of the tenant
     * @return MonetizationPlatformCustomer info of Billing Engine Shared Customer
     * @throws StripeMonetizationException If Failed To get Billing Engine Platform Shared details
     */
    public MonetizationSharedCustomer getSharedCustomer(int applicationId, String apiProvider,
                                                        int tenantId) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        MonetizationSharedCustomer monetizationSharedCustomer = new MonetizationSharedCustomer();
        String sqlQuery = StripeMonetizationConstants.GET_BE_SHARED_CUSTOMER_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, applicationId);
            ps.setString(2, apiProvider);
            ps.setInt(3, tenantId);
            result = ps.executeQuery();
            if (result.next()) {
                monetizationSharedCustomer.setId(result.getInt("ID"));
                monetizationSharedCustomer.setSharedCustomerId(result.getString("SHARED_CUSTOMER_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing Engine Shared Customer details for application with ID : " +
                    applicationId;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
        return monetizationSharedCustomer;
    }

    /**
     * Remove billing engine subscription info
     *
     * @param id Id of the Subscription Info
     * @throws StripeMonetizationException If failed to delete subscription details
     */
    public void removeMonetizedSubscription(int id) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        String sqlQuery = StripeMonetizationConstants.DELETE_BE_SUBSCRIPTION_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            String errorMessage = "Failed to remove monetization info from DB of subscription with ID : " + id;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
    }

    /**
     * Get billing engine Subscription info
     *
     * @param apiUuid       UUID of the API
     * @param apiName       api name
     * @param applicationId Id of the Application
     * @param tenantDomain  tenant domain
     * @return MonetizationSubscription info of Billing Engine Subscription
     * @throws StripeMonetizationException If Failed To get Billing Engine Subscription details
     */
    /**
     * Save a Stripe Checkout Session linked to an APIM pending workflow
     *
     * @param sessionId         Stripe checkout session ID (cs_xxxx)
     * @param workflowReference APIM workflow reference (subscription ID)
     * @param subscriberId      APIM subscriber ID
     * @param tenantId          tenant ID
     * @param apiUuid           UUID of the API being subscribed to
     * @throws StripeMonetizationException if failed to save checkout session
     */
    public void saveCheckoutSession(String sessionId, String workflowReference, int subscriberId,
            int tenantId, String apiUuid, String checkoutUrl) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(StripeMonetizationConstants.ADD_CHECKOUT_SESSION_SQL);
            ps.setString(1, sessionId);
            ps.setString(2, workflowReference);
            ps.setInt(3, subscriberId);
            ps.setInt(4, tenantId);
            ps.setString(5, apiUuid);
            ps.setString(6, checkoutUrl);
            ps.setString(7, StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_PENDING);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed saveCheckoutSession operation", ex);
                }
            }
            String errorMessage = "Failed to save Stripe checkout session : " + sessionId +
                    " for workflow reference : " + workflowReference;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, null);
        }
    }

    /**
     * Returns the Stripe Checkout URL for a pending checkout session identified by its APIM
     * workflow reference (subscription ID).
     *
     * @param workflowReference APIM internal workflow reference (subscription ID)
     * @return checkout URL string, or {@code null} if no pending session is found
     * @throws StripeMonetizationException if a database error occurs
     */
    public String getCheckoutUrlByWorkflowRef(String workflowReference) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(StripeMonetizationConstants.GET_CHECKOUT_URL_BY_WORKFLOW_REF_SQL);
            ps.setString(1, workflowReference);
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("CHECKOUT_URL");
            }
            return null;
        } catch (SQLException e) {
            String errorMessage = "Failed to retrieve checkout URL for workflow reference: " + workflowReference;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
    }

    /**
     * Get Stripe Checkout Session record by Stripe session ID
     *
     * @param sessionId Stripe checkout session ID (cs_xxxx)
     * @return Map containing session fields, or empty map if not found
     * @throws StripeMonetizationException if failed to retrieve checkout session
     */
    public Map<String, String> getCheckoutSession(String sessionId) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        Map<String, String> sessionData = new HashMap<>();
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(StripeMonetizationConstants.GET_CHECKOUT_SESSION_BY_SESSION_ID_SQL);
            ps.setString(1, sessionId);
            rs = ps.executeQuery();
            if (rs.next()) {
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_SESSION_ID,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_SESSION_ID));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_WORKFLOW_REF,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_WORKFLOW_REF));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_SUBSCRIBER_ID,
                        String.valueOf(rs.getInt(StripeMonetizationConstants.CHECKOUT_COL_SUBSCRIBER_ID)));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_TENANT_ID,
                        String.valueOf(rs.getInt(StripeMonetizationConstants.CHECKOUT_COL_TENANT_ID)));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_API_UUID,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_API_UUID));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_STATUS,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_STATUS));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to retrieve Stripe checkout session : " + sessionId;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return sessionData;
    }

    /**
     * Get Stripe Checkout Session record by APIM workflow reference
     *
     * @param workflowReference APIM workflow reference (subscription ID)
     * @return Map containing session fields, or empty map if not found
     * @throws StripeMonetizationException if failed to retrieve checkout session
     */
    public Map<String, String> getCheckoutSessionByWorkflowRef(String workflowReference)
            throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        Map<String, String> sessionData = new HashMap<>();
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(StripeMonetizationConstants.GET_CHECKOUT_SESSION_BY_WORKFLOW_REF_SQL);
            ps.setString(1, workflowReference);
            rs = ps.executeQuery();
            if (rs.next()) {
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_SESSION_ID,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_SESSION_ID));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_WORKFLOW_REF,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_WORKFLOW_REF));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_SUBSCRIBER_ID,
                        String.valueOf(rs.getInt(StripeMonetizationConstants.CHECKOUT_COL_SUBSCRIBER_ID)));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_TENANT_ID,
                        String.valueOf(rs.getInt(StripeMonetizationConstants.CHECKOUT_COL_TENANT_ID)));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_API_UUID,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_API_UUID));
                sessionData.put(StripeMonetizationConstants.CHECKOUT_COL_STATUS,
                        rs.getString(StripeMonetizationConstants.CHECKOUT_COL_STATUS));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to retrieve Stripe checkout session for workflow reference : " +
                    workflowReference;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return sessionData;
    }

    /**
     * Atomically claims a Stripe Checkout Session for processing by transitioning its
     * status from {@code PENDING} to {@code IN_PROGRESS}.
     *
     * <p>This is the idempotency guard for the first-one-wins parallel completion strategy.
     * Both the webhook path and the browser-redirect path call {@code complete()} on the
     * workflow executor, which calls this method first. Only the winner (rowsAffected == 1)
     * proceeds with Stripe API calls; the other sees 0 rows and skips.
     *
     * @param sessionId Stripe checkout session ID (cs_xxxx)
     * @return {@code true} if the claim succeeded (this caller should process the session);
     *         {@code false} if the session was already claimed or completed
     * @throws StripeMonetizationException if a database error occurs
     */
    public boolean claimCheckoutSession(String sessionId) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(StripeMonetizationConstants.CLAIM_CHECKOUT_SESSION_SQL);
            ps.setString(1, sessionId);
            int rowsAffected = ps.executeUpdate();
            conn.commit();
            return rowsAffected == 1;
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error rolling back claimCheckoutSession for session: " + sessionId, ex);
                }
            }
            String errorMessage = "Failed to claim Stripe checkout session: " + sessionId;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, null);
        }
    }

    /**
     * Resets a claimed Stripe Checkout Session from {@code IN_PROGRESS} back to {@code PENDING}.
     *
     * <p>Called when {@code completeStripeCheckoutSubscription()} throws an exception so
     * that the other completion path (webhook or browser-redirect) can retry the session.
     *
     * @param sessionId Stripe checkout session ID (cs_xxxx)
     * @throws StripeMonetizationException if a database error occurs
     */
    public void resetCheckoutSessionClaim(String sessionId) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(StripeMonetizationConstants.RESET_CHECKOUT_SESSION_CLAIM_SQL);
            ps.setString(1, sessionId);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error rolling back resetCheckoutSessionClaim for session: " + sessionId, ex);
                }
            }
            String errorMessage = "Failed to reset claim for Stripe checkout session: " + sessionId;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, null);
        }
    }

    /**
     * Update the status of a Stripe Checkout Session record
     *
     * @param sessionId Stripe checkout session ID (cs_xxxx)
     * @param status    new status value (PENDING / COMPLETED / EXPIRED)
     * @throws StripeMonetizationException if failed to update checkout session status
     */
    public void updateCheckoutSessionStatus(String sessionId, String status) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(StripeMonetizationConstants.UPDATE_CHECKOUT_SESSION_STATUS_SQL);
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, sessionId);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    log.error("Error while rolling back the failed updateCheckoutSessionStatus operation", ex);
                }
            }
            String errorMessage = "Failed to update status of Stripe checkout session : " + sessionId;
            log.error(errorMessage, e);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, null);
        }
    }

    public MonetizedSubscription getMonetizedSubscription(String apiUuid, String apiName, int applicationId,
            String tenantDomain) throws StripeMonetizationException {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet result = null;
        MonetizedSubscription monetizedSubscription = new MonetizedSubscription();
        int tenantId = APIUtil.getTenantIdFromTenantDomain(tenantDomain);
        String sqlQuery = StripeMonetizationConstants.GET_BE_SUBSCRIPTION_SQL;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setInt(1, applicationId);
            ps.setString(2, apiUuid);
            ps.setInt(3, tenantId);
            result = ps.executeQuery();
            if (result.next()) {
                monetizedSubscription.setId(result.getInt("ID"));
                monetizedSubscription.setSubscriptionId(result.getString("SUBSCRIPTION_ID"));
            }
        } catch (SQLException e) {
            String errorMessage = "Failed to get billing engine Subscription info for API : " + apiName;
            log.error(errorMessage);
            throw new StripeMonetizationException(errorMessage, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, result);
        }
        return monetizedSubscription;
    }
}
