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
 * This class is to define the properties of shared customer in stripe
 */
public class MonetizationSharedCustomer {

    private int id;
    private int applicationId;
    private String apiProvider;
    private int tenantId;
    private String sharedCustomerId;
    private int parentCustomerId;

    public String getApiProvider() {
        return apiProvider;
    }

    public void setApiProvider(String apiProvider) {
        this.apiProvider = apiProvider;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(int applicationId) {
        this.applicationId = applicationId;
    }

    public int getTenantId() {
        return tenantId;
    }

    public void setTenantId(int tenantId) {
        this.tenantId = tenantId;
    }

    public String getSharedCustomerId() {
        return sharedCustomerId;
    }

    public void setSharedCustomerId(String sharedCustomerId) {
        this.sharedCustomerId = sharedCustomerId;
    }

    public int getParentCustomerId() {
        return parentCustomerId;
    }

    public void setParentCustomerId(int parentCustomerId) {
        this.parentCustomerId = parentCustomerId;
    }
}
