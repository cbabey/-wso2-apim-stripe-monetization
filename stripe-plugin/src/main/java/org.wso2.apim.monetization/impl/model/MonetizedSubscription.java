/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.apim.monetization.impl.model;

/**
 * This class is to define the properties of monetized subscription
 **/
public class MonetizedSubscription {

    private int id;
    private int subscribedApiId;
    private int subscribedApplicationId;
    private int tenantId;
    private String subscriptionId;
    private int sharedCustomerId;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getSubscribedApiId() {
        return subscribedApiId;
    }

    public void setSubscribedApiId(int subscribedApiId) {
        this.subscribedApiId = subscribedApiId;
    }

    public int getSubscribedApplicationId() {
        return subscribedApplicationId;
    }

    public void setSubscribedApplicationId(int subscribedApplicationId) {
        this.subscribedApplicationId = subscribedApplicationId;
    }

    public int getTenantId() {
        return tenantId;
    }

    public void setTenantId(int tenantId) {
        this.tenantId = tenantId;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public int getSharedCustomerId() {
        return sharedCustomerId;
    }

    public void setSharedCustomerId(int sharedCustomerId) {
        this.sharedCustomerId = sharedCustomerId;
    }
}
