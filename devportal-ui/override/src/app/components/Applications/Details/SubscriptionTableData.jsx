/* eslint-disable no-nested-ternary */
/*
 * Copyright (c), WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import React from 'react';
import { Link } from 'react-router-dom';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';
import Icon from '@mui/material/Icon';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import Dialog from '@mui/material/Dialog';
import Slide from '@mui/material/Slide';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Autocomplete from '@mui/material/Autocomplete';
import HelpOutline from '@mui/icons-material/HelpOutline';
import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import PaymentIcon from '@mui/icons-material/Payment';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { FormattedMessage } from 'react-intl';
import { ScopeValidation, resourceMethods, resourcePaths } from 'AppComponents/Shared/ScopeValidation';
import PropTypes from 'prop-types';
import Api from 'AppData/api';
import CONSTANTS from 'AppData/Constants';
import Subscription from 'AppData/Subscription';
import { mdiOpenInNew } from '@mdi/js';
import { Icon as MDIcon } from '@mdi/react';
import Popover from '@mui/material/Popover';
import Invoice from './Invoice';
import WebHookDetails from './WebHookDetails';

/**
 *
 *
 * @class SubscriptionTableData
 * @extends {React.Component}
 */
class SubscriptionTableData extends React.Component {
    /**
     *Creates an instance of SubscriptionTableData.
     * @param {*} props properties
     * @memberof SubscriptionTableData
     */
    constructor(props) {
        super(props);
        this.state = {
            openMenu: false,
            openMenuEdit: false,
            isMonetizedAPI: false,
            isDynamicUsagePolicy: false,
            tiers: [],
            selectedTier: '',
            isWebhookAPI: false,
            callbackLinkAnchor: null,
            manageBillingLoading: false,
            resumeCheckoutLoading: false,
        };
        this.handleRequestClose = this.handleRequestClose.bind(this);
        this.handleRequestOpen = this.handleRequestOpen.bind(this);
        this.handleRequestDelete = this.handleRequestDelete.bind(this);
        this.checkIfDynamicUsagePolicy = this.checkIfDynamicUsagePolicy.bind(this);
        this.checkIfMonetizedAPI = this.checkIfMonetizedAPI.bind(this);
        this.populateSubscriptionTiers = this.populateSubscriptionTiers.bind(this);
        this.handleSubscriptionTierUpdate = this.handleSubscriptionTierUpdate.bind(this);
        this.handleRequestCloseEditMenu = this.handleRequestCloseEditMenu.bind(this);
        this.handleRequestOpenEditMenu = this.handleRequestOpenEditMenu.bind(this);
        this.setSelectedTier = this.setSelectedTier.bind(this);
        this.checkIfWebhookAPI = this.checkIfWebhookAPI.bind(this);
        this.handleOpenCallbackURLs = this.handleOpenCallbackURLs.bind(this);
        this.handleCloseCallbackURLs = this.handleCloseCallbackURLs.bind(this);
        this.handleManageBilling = this.handleManageBilling.bind(this);
        this.handleResumeCheckout = this.handleResumeCheckout.bind(this);
    }

    componentDidMount() {
        this.checkIfMonetizedAPI(this.props.subscription.apiId);
        this.checkIfDynamicUsagePolicy(this.props.subscription.subscriptionId);
        this.populateSubscriptionTiers(this.props.subscription.apiId);
        this.checkIfWebhookAPI();
    }

    /**
    *
    *
    * @memberof SubscriptionTableData
    */
    setSelectedTier(e) {
        this.setState({ selectedTier: e });
    }

    /**
     *
     * Handle onclick for subscription delete
     * @param {*} subscriptionId subscription id
     * @memberof SubscriptionTableData
     */
    handleRequestDelete(subscriptionId) {
        const { handleSubscriptionDelete } = this.props;
        this.setState({ openMenu: false });
        if (handleSubscriptionDelete) {
            handleSubscriptionDelete(subscriptionId);
        }
    }

    /**
     *
     *
     * @memberof SubscriptionTableData
     */
    handleRequestCloseEditMenu() {
        this.setState({ openMenuEdit: false });
    }

    /**
    *
    *
    * @memberof SubscriptionTableData
    */
    handleRequestOpenEditMenu() {
        this.setState({ openMenuEdit: true });
    }

    /**
    * @memberof SubscriptionTableData
    */
    handleRequestOpen() {
        this.setState({ openMenu: true });
    }

    /**
     * @memberof SubscriptionTableData
     */
    handleRequestClose() {
        this.setState({ openMenu: false });
    }

    /**
     *
     * Handle onclick for subscription update
     * @param {*} apiId subscription id
     * @param {*} subscriptionId subscription id
     * @param {*} throttlingPolicy throttling tier
     * @param {*} status subscription status
     * @memberof SubscriptionTableData
     */
    handleSubscriptionTierUpdate(apiId, subscriptionId, requestedThrottlingPolicy, status, currentThrottlingPolicy) {
        const { handleSubscriptionUpdate } = this.props;
        this.setState({ openMenuEdit: false });
        if (handleSubscriptionUpdate) {
            handleSubscriptionUpdate(apiId, subscriptionId, currentThrottlingPolicy, status, requestedThrottlingPolicy);
        }
    }

    /**
     * Getting the policies from api details
     *
     */
    populateSubscriptionTiers(apiUUID) {
        const apiClient = new Api();
        const promisedApi = apiClient.getAPIById(apiUUID);
        promisedApi.then((response) => {
            if (response && response.data) {
                const api = JSON.parse(response.data);
                const apiTiers = api.tiers;
                const tiers = [];
                for (let i = 0; i < apiTiers.length; i++) {
                    const { tierName } = apiTiers[i];
                    tiers.push({ value: tierName, label: tierName });
                }
                this.setState({ tiers });
            }
        });
    }

    /**
     * Check if the API is monetized
     * @param apiUUID API UUID
     */
    checkIfMonetizedAPI(apiUUID) {
        const apiClient = new Api();
        const promisedApi = apiClient.getAPIById(apiUUID);
        promisedApi.then((response) => {
            if (response && response.data) {
                const apiData = JSON.parse(response.data);
                this.setState({ isMonetizedAPI: apiData.monetization.enabled });
            }
        });
    }

    /**
     * Check if the policy is dynamic usage type
     * @param subscriptionUUID subscription UUID
     */
    checkIfDynamicUsagePolicy(subscriptionUUID) {
        const client = new Subscription();
        const promisedSubscription = client.getSubscription(subscriptionUUID);
        promisedSubscription.then((response) => {
            if (response && response.body) {
                const subscriptionData = JSON.parse(response.data);
                if (subscriptionData.throttlingPolicy) {
                    const apiClient = new Api();
                    const promisedPolicy = apiClient.getTierByName(subscriptionData.throttlingPolicy, 'subscription');
                    promisedPolicy.then((policyResponse) => {
                        const policyData = JSON.parse(policyResponse.data);
                        if (policyData.monetizationAttributes.billingType
                            && (policyData.monetizationAttributes.billingType
                                === 'DYNAMICRATE')) {
                            this.setState({ isDynamicUsagePolicy: true });
                        }
                    });
                }
            }
        });
    }

    /**
     * Check if the API is a webhook API
     */
    checkIfWebhookAPI() {
        this.setState({ isWebhookAPI: this.props.subscription.apiInfo.type === CONSTANTS.API_TYPES.WEBSUB });
    }

    /**
     * Handle open click for view webhook URLs
     * @param {*} event click event
     * @memberof SubscriptionTableData
     */
    handleOpenCallbackURLs(event) {
        this.setState({ callbackLinkAnchor: event.currentTarget });
    }

    /**
     * Handle close for view webhook URLs
     * @memberof SubscriptionTableData
     */
    handleCloseCallbackURLs() {
        this.setState({ callbackLinkAnchor: null });
    }

    /**
     * Opens the Stripe Customer Portal for the application in a new tab.
     * Calls the billing-portal endpoint to get a short-lived portal URL,
     * then opens it in a new browser tab.
     * @memberof SubscriptionTableData
     */
    handleManageBilling() {
        const { applicationId } = this.props;
        if (!applicationId) {
            return;
        }
        this.setState({ manageBillingLoading: true });

        const returnUrl = window.location.href;
        fetch(
            `/api/am/stripe/billing-portal?applicationId=${encodeURIComponent(applicationId)}`
                + `&returnUrl=${encodeURIComponent(returnUrl)}`,
        )
            .then((res) => res.json())
            .then((data) => {
                if (data.url) {
                    window.open(data.url, '_blank', 'noopener,noreferrer');
                } else {
                    console.error('billing-portal: no URL in response', data);
                }
            })
            .catch((err) => {
                console.error('billing-portal: request failed', err);
            })
            .finally(() => {
                this.setState({ manageBillingLoading: false });
            });
    }

    /**
     * Handles the "Resume Checkout" button for subscriptions stuck in ON_HOLD.
     *
     * Two-step strategy:
     *  1. Call complete-session with the stored Stripe session ID.
     *     This handles the common case where the payment already went through
     *     (card was entered, Stripe processed it) but the webhook or activation
     *     step failed — re-triggering complete() reads the completed Stripe session
     *     and activates the APIM subscription without needing a new checkout.
     *  2. Only if step 1 says the session is not yet paid (non-2xx), redirect the
     *     user to the Stripe Checkout URL so they can actually enter their card.
     *  3. If the session is not found at all (404), the session has expired — tell
     *     the user to delete and re-subscribe.
     */
    handleResumeCheckout() {
        const { subscription: { subscriptionId } } = this.props;
        this.setState({ resumeCheckoutLoading: true });

        fetch(`/api/am/stripe/checkout-url?subscriptionId=${encodeURIComponent(subscriptionId)}`)
            .then((res) => {
                if (res.status === 404) {
                    // No pending session in DB — session fully expired or already activated
                    /* eslint-disable no-alert */
                    alert(
                        'Your payment session has expired.\n'
                        + 'Please delete this subscription and re-subscribe to start a new checkout.',
                    );
                    /* eslint-enable no-alert */
                    return null;
                }
                return res.json();
            })
            .then((data) => {
                if (!data) return null;
                const { checkoutUrl, sessionId } = data;

                if (!sessionId) {
                    // Older response format — just redirect
                    if (checkoutUrl) window.location.href = checkoutUrl;
                    return null;
                }

                // Step 1 — try to activate via the existing Stripe session.
                // If the payment already went through (card was entered) this will
                // succeed and activate the subscription without a new checkout page.
                return fetch(
                    `/api/am/stripe/complete-session?session_id=${encodeURIComponent(sessionId)}`,
                    { method: 'POST' },
                ).then((completeRes) => {
                    if (completeRes.ok) {
                        // Activation succeeded (or was already in progress) — reload
                        // so the subscription table reflects the new UNBLOCKED status.
                        window.location.reload();
                    } else if (completeRes.status === 402) {
                        // Stripe subscription payment declined. The subscription ID was saved
                        // to the DB; Stripe will retry automatically.
                        /* eslint-disable no-alert */
                        alert(
                            'Your initial payment was declined.\n'
                            + 'Stripe will retry automatically.\n'
                            + 'You can also delete this subscription and re-subscribe'
                            + ' with a different card.',
                        );
                        /* eslint-enable no-alert */
                    } else if (checkoutUrl) {
                        // Other failure — the Stripe Checkout may not have been completed
                        // yet; redirect so the user can finish entering their card.
                        window.location.href = checkoutUrl;
                    } else {
                        /* eslint-disable no-alert */
                        alert(
                            'Your payment session has expired.\n'
                            + 'Please delete this subscription and re-subscribe.',
                        );
                        /* eslint-enable no-alert */
                    }
                });
            })
            .catch((err) => {
                console.error('resume-checkout: request failed', err);
            })
            .finally(() => {
                this.setState({ resumeCheckoutLoading: false });
            });
    }

    /**
    * @inheritdoc
    * @memberof SubscriptionTableData
    */
    render() {
        const {
            subscription: {
                apiInfo, status, throttlingPolicy, subscriptionId, apiId, requestedThrottlingPolicy,
            },
        } = this.props;
        const {
            openMenu, isMonetizedAPI, isDynamicUsagePolicy, openMenuEdit, selectedTier, tiers,
            isWebhookAPI, callbackLinkAnchor, manageBillingLoading, resumeCheckoutLoading,
        } = this.state;
        const isSubValidationDisabled = tiers && tiers.length === 1
            && tiers[0].value.includes(CONSTANTS.DEFAULT_SUBSCRIPTIONLESS_PLAN);
        const link = (
            <Link
                to={tiers.length === 0 ? '' : '/apis/' + apiId}
                style={{ cursor: tiers.length === 0 ? 'default' : '' }}
                external
            >
                {apiInfo.name + ' - ' + apiInfo.version + ' '}
                <MDIcon path={mdiOpenInNew} size='12px' />
            </Link>
        );
        const openWebhookURL = Boolean(callbackLinkAnchor);
        const webhookURLPopoverId = openWebhookURL ? 'simple-popover' : undefined;
        const callBackUrlLink = (
            <a
                aria-describedby={webhookURLPopoverId}
                style={{
                    fontSize: '0.6rem', color: '#072938', textDecoration: 'underline', paddingLeft: '10px',
                }}
                onClick={(event) => this.handleOpenCallbackURLs(event)}
                onKeyDown={(event) => (event.key === 'Enter') && this.handleOpenCallbackURLs(event)}
                role='button'
                tabIndex={0}
            >
                View Callback URLs
            </a>
        );
        return (
            !isSubValidationDisabled && (
                <TableRow hover>
                    <TableCell>
                        {link}
                        {isWebhookAPI && (
                            <>
                                {callBackUrlLink}
                                <Popover
                                    id={webhookURLPopoverId}
                                    open={openWebhookURL}
                                    anchorEl={callbackLinkAnchor}
                                    onClose={this.handleCloseCallbackURLs}
                                    anchorOrigin={{
                                        vertical: 'bottom',
                                        horizontal: 'left',
                                    }}
                                >
                                    <div>
                                        <WebHookDetails
                                            applicationId={this.props.subscription.applicationId}
                                            apiId={this.props.subscription.apiId}
                                        />
                                    </div>
                                </Popover>
                            </>
                        )}
                    </TableCell>
                    <TableCell>{apiInfo.lifeCycleStatus}</TableCell>
                    {throttlingPolicy.includes(CONSTANTS.DEFAULT_SUBSCRIPTIONLESS_PLAN) ? (
                        <TableCell>
                            {throttlingPolicy}
                            {' '}
                            <Tooltip
                                placement='bottom'
                                interactive
                                aria-label='helper text for default subscription policy'
                                title={(
                                    <>
                                        <FormattedMessage
                                            id='Applications.Details.SubscriptionTableData.policy.default.tooltip'
                                            defaultMessage='This is the default subscription policy used when
                                            subscription validation was disabled.'
                                        />
                                    </>
                                )}
                                sx={{
                                    backgroundColor: '#f5f5f9',
                                    color: 'rgba(0, 0, 0, 0.87)',
                                    maxWidth: 220,
                                    fontSize: '12px',
                                    border: '1px solid #dadde9',
                                }}
                            >
                                <Box
                                    component='span'
                                    sx={{
                                        display: 'inline-flex',
                                        verticalAlign: 'middle',
                                        fontSize: '16px',
                                    }}
                                >
                                    <HelpOutline
                                        sx={{
                                            fontSize: 'inherit',
                                        }}
                                    />
                                </Box>
                            </Tooltip>
                        </TableCell>
                    ) : (
                        <TableCell>{throttlingPolicy}</TableCell>
                    )}
                    <TableCell>
                        {/* Show a payment-failed warning chip alongside BLOCKED status
                            so the user understands why they are blocked and what to do */}
                        {(status === 'BLOCKED' && isMonetizedAPI) ? (
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                                <span>{status}</span>
                                <Chip
                                    icon={<WarningAmberIcon sx={{ fontSize: '14px !important' }} />}
                                    label={(
                                        <FormattedMessage
                                            id='Applications.Details.SubscriptionTableData.payment.failed.chip'
                                            defaultMessage='Payment failed'
                                        />
                                    )}
                                    color='warning'
                                    size='small'
                                    variant='outlined'
                                />
                            </Box>
                        ) : status}
                    </TableCell>
                    <TableCell>
                        <Button
                            id={'edit-api-subscription-' + apiId}
                            color='grey'
                            onClick={this.handleRequestOpenEditMenu}
                            startIcon={<Icon>edit</Icon>}
                            disabled={tiers.length === 0}
                        >
                            <FormattedMessage
                                id='Applications.Details.SubscriptionTableData.edit.text'
                                defaultMessage='Edit'
                            />
                        </Button>
                        <Dialog open={openMenuEdit} transition={Slide}>
                            <DialogTitle>
                                <FormattedMessage
                                    id='Applications.Details.SubscriptionTableData.update.subscription'
                                    defaultMessage='Update Subscription'
                                />
                            </DialogTitle>
                            <DialogContent>
                                <DialogContentText>
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.update.business.plan'
                                        defaultMessage='Current Business Plan : '
                                    />
                                    {throttlingPolicy}
                                    <div>
                                        {(status === 'BLOCKED')
                                            ? (
                                                <FormattedMessage
                                                    id={'Applications.Details.SubscriptionTableData.update.'
                                                        + 'throttling.policy.blocked'}
                                                    defaultMessage={'Subscription is in BLOCKED state. '
                                                        + 'You need to unblock the subscription in order to edit the tier'}
                                                />
                                            )
                                            : (status === 'ON_HOLD')
                                                ? (
                                                    <FormattedMessage
                                                        id={'Applications.Details.SubscriptionTableData.update.'
                                                            + 'throttling.policy.onHold'}
                                                        defaultMessage={'Subscription is currently ON_HOLD state.'
                                                            + ' You need to get approval to the subscription before editing the tier'}
                                                    />
                                                )
                                                : (status === 'REJECTED')
                                                    ? (
                                                        <FormattedMessage
                                                            id={'Applications.Details.SubscriptionTableData.update.'
                                                                + 'throttling.policy.rejected'}
                                                            defaultMessage={'Subscription is currently REJECTED state.'
                                                                + ' You need to get approval to the subscription before editing the tier'}
                                                        />
                                                    )
                                                    : (status === 'TIER_UPDATE_PENDING')
                                                        ? (
                                                            <FormattedMessage
                                                                id={'Applications.Details.SubscriptionTableData.update.'
                                                                    + 'throttling.policy.tierUpdatePending'}
                                                                defaultMessage={'Subscription is currently TIER_UPDATE_PENDING state.'
                                                                    + ' You need to get approval to the existing subscription edit request'
                                                                    + ' before editing the tier'}
                                                            />
                                                        )
                                                        : (
                                                            <div>
                                                                <Autocomplete
                                                                    id='application-policy'
                                                                    disableClearable
                                                                    options={tiers}
                                                                    getOptionLabel={(option) => option.label ?? option}
                                                                    getOptionSelected={(option, value) => option.value === value}
                                                                    value={selectedTier}
                                                                    onChange={(e, newValue) => this.setSelectedTier(newValue.value)}
                                                                    renderInput={(params) => (
                                                                        <TextField
                                                                            id='outlined-select-currency'
                                                                            name='throttlingPolicy'
                                                                            required
                                                                            {...params}
                                                                            label={(
                                                                                <FormattedMessage
                                                                                    defaultMessage='Business Plan'
                                                                                    id={'Applications.Details.SubscriptionTableData.'
                                                                                        + 'update.business.plan.name'}
                                                                                />
                                                                            )}
                                                                            helperText={(
                                                                                <FormattedMessage
                                                                                    defaultMessage={'Assign a new Business plan to the '
                                                                                        + 'existing subscription'}
                                                                                    id={'Applications.Details.SubscriptionTableData.'
                                                                                        + 'update.throttling.policy.helper'}
                                                                                />
                                                                            )}
                                                                            margin='normal'
                                                                            variant='outlined'
                                                                        />
                                                                    )}
                                                                />
                                                                {(status === 'TIER_UPDATE_PENDING')
                                                                    && (
                                                                        <div>
                                                                            <FormattedMessage
                                                                                id={'Applications.Details.SubscriptionTableData.update.'
                                                                                    + 'throttling.policy.tier.update'}
                                                                                defaultMessage='Pending Tier Update : '
                                                                            />
                                                                            {requestedThrottlingPolicy}
                                                                        </div>
                                                                    )}
                                                            </div>
                                                        )}
                                    </div>
                                </DialogContentText>
                            </DialogContent>
                            <DialogActions>
                                <Button dense color='grey' onClick={this.handleRequestCloseEditMenu}>
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.cancel'
                                        defaultMessage='Cancel'
                                    />
                                </Button>
                                <Button
                                    variant='contained'
                                    disabled={(status === 'BLOCKED' || status === 'ON_HOLD' || status === 'REJECTED'
                                        || status === 'TIER_UPDATE_PENDING')}
                                    dense
                                    color='primary'
                                    onClick={() => this.handleSubscriptionTierUpdate(apiId,
                                        subscriptionId, selectedTier, status, throttlingPolicy)}
                                    data-testid='subscription-tier-update-button'
                                >
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.update'
                                        defaultMessage='Update'
                                    />
                                </Button>
                            </DialogActions>
                        </Dialog>
                        <ScopeValidation
                            resourcePath={resourcePaths.SINGLE_SUBSCRIPTION}
                            resourceMethod={resourceMethods.DELETE}
                        >
                            <Button
                                id={'delete-api-subscription-' + apiId}
                                color='grey'
                                onClick={this.handleRequestOpen}
                                startIcon={<Icon>delete</Icon>}
                                disabled={tiers.length === 0 || status === 'DELETE_PENDING'}
                            >
                                <FormattedMessage
                                    id='Applications.Details.SubscriptionTableData.delete.text'
                                    defaultMessage='Delete'
                                />
                            </Button>
                        </ScopeValidation>

                        <Dialog open={openMenu} transition={Slide}>
                            <DialogTitle>
                                <FormattedMessage
                                    id='Applications.Details.SubscriptionTableData.delete.subscription.confirmation.dialog.title'
                                    defaultMessage='Confirm'
                                />
                            </DialogTitle>
                            <DialogContent>
                                <DialogContentText>
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.delete.subscription.confirmation'
                                        defaultMessage='Are you sure you want to delete the Subscription?'
                                    />
                                </DialogContentText>
                            </DialogContent>
                            <DialogActions>
                                <Button dense color='grey' onClick={this.handleRequestClose}>
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.cancel'
                                        defaultMessage='Cancel'
                                    />
                                </Button>
                                <Button
                                    id='delete-api-subscription-confirm-btn'
                                    dense
                                    variant='contained'
                                    color='primary'
                                    onClick={() => this.handleRequestDelete(subscriptionId)}
                                >
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.delete'
                                        defaultMessage='Delete'
                                    />
                                </Button>
                            </DialogActions>
                        </Dialog>
                        {isMonetizedAPI && (
                            <Invoice
                                tiers={tiers}
                                subscriptionId={subscriptionId}
                                isDynamicUsagePolicy={isDynamicUsagePolicy}
                            />
                        )}
                        {/* ON_HOLD: payment never completed on the initial checkout.
                            Show "Resume Checkout" so the user can go back to Stripe and
                            retry — or learn the session has expired and must re-subscribe. */}
                        {isMonetizedAPI && status === 'ON_HOLD' && (
                            <Tooltip
                                title={(
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.resume.checkout.tooltip'
                                        defaultMessage='Your payment is incomplete. Click to return to the Stripe payment page.'
                                    />
                                )}
                            >
                                <span>
                                    <Button
                                        id={'resume-checkout-' + apiId}
                                        color='warning'
                                        onClick={this.handleResumeCheckout}
                                        disabled={resumeCheckoutLoading}
                                        startIcon={resumeCheckoutLoading
                                            ? <CircularProgress size={14} />
                                            : <PaymentIcon />}
                                        size='small'
                                    >
                                        <FormattedMessage
                                            id='Applications.Details.SubscriptionTableData.resume.checkout'
                                            defaultMessage='Resume Checkout'
                                        />
                                    </Button>
                                </span>
                            </Tooltip>
                        )}
                        {/* Manage Billing — shown only when a Stripe billing account exists
                            (i.e. checkout completed at least once). ON_HOLD is excluded because
                            no shared customer record exists yet so the portal would error. */}
                        {isMonetizedAPI && status !== 'ON_HOLD' && (
                            <Tooltip
                                title={(
                                    <FormattedMessage
                                        id='Applications.Details.SubscriptionTableData.manage.billing.tooltip'
                                        defaultMessage={status === 'BLOCKED'
                                            ? 'Update your payment method to restore API access'
                                            : 'View invoices and manage your payment method in Stripe'}
                                    />
                                )}
                            >
                                <span>
                                    <Button
                                        id={'manage-billing-' + apiId}
                                        color={status === 'BLOCKED' ? 'warning' : 'grey'}
                                        onClick={this.handleManageBilling}
                                        disabled={manageBillingLoading || !this.props.applicationId}
                                        startIcon={manageBillingLoading
                                            ? <CircularProgress size={14} />
                                            : <PaymentIcon />}
                                        endIcon={<OpenInNewIcon sx={{ fontSize: '12px !important' }} />}
                                        size='small'
                                    >
                                        <FormattedMessage
                                            id='Applications.Details.SubscriptionTableData.manage.billing'
                                            defaultMessage='Manage Billing'
                                        />
                                    </Button>
                                </span>
                            </Tooltip>
                        )}
                    </TableCell>
                </TableRow>
            )
        );
    }
}
SubscriptionTableData.propTypes = {
    subscription: PropTypes.shape({
        apiInfo: PropTypes.shape({
            name: PropTypes.string.isRequired,
            version: PropTypes.string.isRequired,
            lifeCycleStatus: PropTypes.string.isRequired,
        }).isRequired,
        throttlingPolicy: PropTypes.string.isRequired,
        subscriptionId: PropTypes.string.isRequired,
        apiId: PropTypes.string.isRequired,
        status: PropTypes.string.isRequired,
        requestedThrottlingPolicy: PropTypes.string.isRequired,
    }).isRequired,
    handleSubscriptionDelete: PropTypes.func.isRequired,
    handleSubscriptionUpdate: PropTypes.func.isRequired,
    applicationId: PropTypes.string.isRequired,
};
export default SubscriptionTableData;
