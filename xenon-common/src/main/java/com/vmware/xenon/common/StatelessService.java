/*
 * Copyright (c) 2014-2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.common;

import java.net.URI;
import java.util.EnumSet;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.vmware.xenon.common.Operation.AuthorizationContext;
import com.vmware.xenon.common.OperationProcessingChain.OperationProcessingContext;
import com.vmware.xenon.common.RequestRouter.Route.RouteDocumentation;
import com.vmware.xenon.common.RequestRouter.Route.SupportLevel;
import com.vmware.xenon.common.ServiceHost.ServiceNotFoundException;
import com.vmware.xenon.common.ServiceStats.ServiceStat;
import com.vmware.xenon.common.jwt.Signer;
import com.vmware.xenon.common.jwt.Verifier;
import com.vmware.xenon.services.common.ServiceUriPaths;

/**
 * Infrastructure use only. Minimal Service implementation. Core service implementations that do not
 * require synchronization, queuing or advanced options use this as the base class.
 */
public class StatelessService implements Service {

    private long maintenanceIntervalMicros;
    private Long cacheClearDelayMicros;
    private OperationProcessingChain opProcessingChain;
    private ProcessingStage stage = ProcessingStage.CREATED;
    private ServiceHost host;
    private String selfLink;
    protected final EnumSet<ServiceOption> options = EnumSet.noneOf(ServiceOption.class);
    private UtilityService utilityService;
    protected Class<? extends ServiceDocument> stateType;

    public StatelessService(Class<? extends ServiceDocument> stateType) {
        if (stateType == null) {
            throw new IllegalArgumentException("stateType is required");
        }
        this.stateType = stateType;
        this.options.add(ServiceOption.STATELESS);
        this.options.add(ServiceOption.CONCURRENT_GET_HANDLING);
        this.options.add(ServiceOption.CONCURRENT_UPDATE_HANDLING);
    }

    public StatelessService() {
        this.stateType = ServiceDocument.class;
        this.options.add(ServiceOption.STATELESS);
    }

    @Override
    public void handleCreate(Operation post) {
        post.complete();
    }

    @Override
    public void handleStart(Operation startPost) {
        startPost.complete();
    }

    @Override
    public void authorizeRequest(Operation op) {
        // A state-less service has no service state to apply policy to, but it does have a
        // self link. Create a document with the service link so we can apply roles with resource
        // specifications targeting the self link field
        ServiceDocument doc = new ServiceDocument();
        doc.documentSelfLink = this.selfLink;
        if (getHost().isAuthorized(this, doc, op)) {
            op.complete();
            return;
        }

        op.fail(Operation.STATUS_CODE_FORBIDDEN);
    }

    @Override
    public boolean queueRequest(Operation op) {
        return false;
    }

    @Override
    public void sendRequest(Operation op) {
        prepareRequest(op);
        this.host.sendRequest(op);
    }

    protected void prepareRequest(Operation op) {
        op.setReferer(UriUtils.buildUri(getHost().getPublicUri(), getSelfLink()));
    }

    @Override
    public void handleRequest(Operation op) {
        handleRequest(op, OperationProcessingStage.PROCESSING_FILTERS);
    }

    @Override
    public void handleRequest(Operation op, OperationProcessingStage opProcessingStage) {
        try {
            if (opProcessingStage == OperationProcessingStage.PROCESSING_FILTERS) {
                OperationProcessingChain opProcessingChain = getOperationProcessingChain();
                if (opProcessingChain != null) {
                    OperationProcessingContext context = opProcessingChain.createContext(getHost());
                    context.setService(this);
                    opProcessingChain.processRequest(op, context, o -> {
                        handleRequest(op, OperationProcessingStage.EXECUTING_SERVICE_HANDLER);
                    });
                    return;
                }
                opProcessingStage = OperationProcessingStage.EXECUTING_SERVICE_HANDLER;
            }

            if (opProcessingStage == OperationProcessingStage.EXECUTING_SERVICE_HANDLER) {

                op.nestCompletion(o -> {
                    if (op.getStatusCode() == Operation.STATUS_CODE_NOT_MODIFIED) {
                        // nullify the body since HTTP-304 cannot have body in response.
                        // It is defined for GET, but not defined for other actions.
                        // For now, apply the same behavior to all http actions.
                        // If we want to apply only for GET, this logic can move to handleGetCompletion
                        op.setBodyNoCloning(null);
                    }
                    op.complete();
                });

                if (op.getAction() == Action.GET) {
                    if (ServiceHost.isForServiceNamespace(this, op)) {
                        handleGet(op);
                        return;
                    }
                    op.nestCompletion(o -> {
                        handleGetCompletion(op);
                    });
                    handleGet(op);
                } else if (op.getAction() == Action.POST) {
                    handlePost(op);
                } else if (op.getAction() == Action.DELETE) {
                    if (ServiceHost.isForServiceNamespace(this, op)) {
                        // this is a request for the namespace, not the service itself.
                        // Call handleDelete but do not nest completion that stops the service.
                        handleDelete(op);
                        return;
                    }
                    if (ServiceHost.isServiceStop(op)) {
                        op.nestCompletion(o -> {
                            handleStopCompletion(op);
                        });
                        handleStop(op);
                    } else {
                        op.nestCompletion(o -> {
                            handleDeleteCompletion(op);
                        });
                        handleDelete(op);
                    }
                } else if (op.getAction() == Action.OPTIONS) {
                    if (ServiceHost.isForServiceNamespace(this, op)) {
                        handleOptions(op);
                        return;
                    }
                    op.nestCompletion(o -> {
                        handleOptionsCompletion(op);
                    });

                    handleOptions(op);
                } else if (op.getAction() == Action.PATCH) {
                    handlePatch(op);
                } else if (op.getAction() == Action.PUT) {
                    handlePut(op);
                }
            }
        } catch (Exception e) {
            op.fail(e);
        }
    }

    @RouteDocumentation(supportLevel = SupportLevel.NOT_SUPPORTED)
    public void handlePut(Operation put) {
        Operation.failActionNotSupported(put);
    }

    @RouteDocumentation(supportLevel = SupportLevel.NOT_SUPPORTED)
    public void handlePatch(Operation patch) {
        Operation.failActionNotSupported(patch);
    }

    public void handleOptions(Operation options) {
        options.setBody(null).complete();
    }

    /**
     * Runs after a DELETE operation, that is not a service stop, completes.
     * It guarantees that the handleStop handler will execute next, and the shared
     * completion that stops the service will run after the stop operation is completed
     */
    protected void handleDeleteCompletion(Operation op) {
        op.nestCompletion((o) -> {
            handleStopCompletion(op);
        });
        handleStop(op);
    }

    /**
     * Stops the service
     */
    protected void handleStopCompletion(Operation op) {
        getHost().stopService(this);
        op.complete();
    }

    protected void handleOptionsCompletion(Operation options) {
        if (!options.hasBody()) {
            options.setBodyNoCloning(getDocumentTemplate());
        }
        options.complete();
    }

    @RouteDocumentation(supportLevel = SupportLevel.NOT_SUPPORTED)
    public void handlePost(Operation post) {
        Operation.failActionNotSupported(post);
    }

    public void handleGet(Operation get) {
        get.complete();
    }

    public void handleDelete(Operation delete) {
        delete.complete();
    }

    @Override
    public void handleStop(Operation delete) {
        delete.complete();
    }

    private void handleGetCompletion(Operation op) {
        if (!this.options.contains(ServiceOption.PERSISTENCE)) {
            op.complete();
            return;
        }

        URI documentQuery = UriUtils.buildDocumentQueryUri(getHost(),
                this.selfLink,
                true,
                false,
                this.options);
        sendRequest(Operation.createGet(documentQuery).setCompletion((o, e) -> {
            if (e != null) {
                op.fail(e);
                return;
            }
            op.setBodyNoCloning(o.getBodyRaw()).complete();
        }));
    }

    /**
     * Infrastructure use. Invoked by host to execute a service handler for a maintenance request.
     * ServiceMaintenanceRequest object is set in the operation body, with the reasons field
     * indicating the maintenance reason. Its invoked when
     *
     * 1) Periodically, if ServiceOption.PERIODIC_MAINTENANCE is set.
     *
     * 2) Node group change.
     *
     * Services should override handlePeriodicMaintenance and handleNodeGroupMaintenance.
     *
     * An implementation of this method that needs to interact with the state of this service must
     * do so as if it were a client of this service. That is: the state of the service should be
     * retrieved by requesting a GET; and the state of the service should be mutated by submitting a
     * PATCH, PUT or DELETE.
     */
    @Override
    public void handleMaintenance(Operation post) {
        ServiceMaintenanceRequest request = post.getBody(ServiceMaintenanceRequest.class);
        if (request.reasons.contains(ServiceMaintenanceRequest.MaintenanceReason.PERIODIC_SCHEDULE)) {
            this.handlePeriodicMaintenance(post);
        } else if (request.reasons.contains(ServiceMaintenanceRequest.MaintenanceReason.NODE_GROUP_CHANGE)) {
            this.handleNodeGroupMaintenance(post);
        } else {
            post.complete();
        }
    }

    /**
     * Invoked by the host periodically, if ServiceOption.PERIODIC_MAINTENANCE is set.
     * ServiceMaintenanceRequest object is set in the operation body, with the reasons field
     * indicating the maintenance reason.
     *
     * An implementation of this method that needs to interact with the state of this service must
     * do so as if it were a client of this service. That is: the state of the service should be
     * retrieved by requesting a GET; and the state of the service should be mutated by submitting a
     * PATCH, PUT or DELETE.
     */
    public void handlePeriodicMaintenance(Operation post) {
        post.complete();
    }

    /**
     * Invoked by the host on node group change.
     * ServiceMaintenanceRequest object is set in the operation body, with the reasons field
     * indicating the maintenance reason.
     *
     * An implementation of this method that needs to interact with the state of this service must
     * do so as if it were a client of this service. That is: the state of the service should be
     * retrieved by requesting a GET; and the state of the service should be mutated by submitting a
     * PATCH, PUT or DELETE.
     */
    public void handleNodeGroupMaintenance(Operation post) {
        post.complete();
    }

    @Override
    public ServiceHost getHost() {
        return this.host;
    }

    @Override
    public String getSelfLink() {
        return this.selfLink;
    }

    @Override
    public URI getUri() {
        if (this.host == null) {
            return null;
        }
        return UriUtils.buildUri(this.host, this.selfLink);
    }

    @Override
    public OperationProcessingChain getOperationProcessingChain() {
        return this.opProcessingChain;
    }

    @Override
    public ProcessingStage getProcessingStage() {
        return this.stage;
    }

    @Override
    public boolean hasOption(ServiceOption cap) {
        return this.options.contains(cap);
    }

    @Override
    public void toggleOption(ServiceOption option, boolean enable) {
        if (enable) {
            if (option == ServiceOption.REPLICATION) {
                throw new IllegalArgumentException("Option is not supported");
            }
            if (option == ServiceOption.OWNER_SELECTION) {
                throw new IllegalArgumentException("Option is not supported");
            }
            if (option == ServiceOption.IDEMPOTENT_POST) {
                throw new IllegalArgumentException("Option is not supported");
            }
        }
        boolean optionsChanged = false;
        if (enable) {
            optionsChanged = this.options.add(option);
        } else {
            optionsChanged = this.options.remove(option);
        }

        if (enable
                && optionsChanged
                && option == ServiceOption.PERIODIC_MAINTENANCE
                && this.stage == ProcessingStage.AVAILABLE) {
            getHost().scheduleServiceMaintenance(this);
        }
    }

    @Override
    public void setStat(String name, double newValue) {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return;
        }
        allocateUtilityService();
        ServiceStat s = getStat(name);
        this.utilityService.setStat(s, newValue);
    }

    @Override
    public void setStat(ServiceStat s, double newValue) {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return;
        }
        allocateUtilityService();
        this.utilityService.setStat(s, newValue);
    }

    @Override
    public void adjustStat(ServiceStat s, double delta) {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return;
        }
        allocateUtilityService();
        this.utilityService.adjustStat(s, delta);
    }

    @Override
    public void adjustStat(String name, double delta) {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return;
        }
        allocateUtilityService();
        ServiceStat s = getStat(name);
        this.utilityService.adjustStat(s, delta);
    }

    @Override
    public ServiceStat getStat(String name) {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return null;
        }
        allocateUtilityService();
        return this.utilityService.getStat(name);
    }

    /**
     * Value indicating whether GET on /available returns 200 or 503
     * The method is a convenience method since it relies on STAT_NAME_AVAILABLE to report
     * availability.
     */
    public void setAvailable(boolean isAvailable) {
        this.toggleOption(ServiceOption.INSTRUMENTATION, true);
        this.setStat(STAT_NAME_AVAILABLE, isAvailable ? STAT_VALUE_TRUE : STAT_VALUE_FALSE);
    }

    /**
     * Value indicating whether GET on /available returns 200 or 503
     */
    public boolean isAvailable() {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return true;
        }
        // processing stage must also indicate service is started
        if (this.stage != ProcessingStage.AVAILABLE) {
            return false;
        }
        ServiceStat st = this.getStat(STAT_NAME_AVAILABLE);
        if (st != null && st.latestValue == STAT_VALUE_TRUE) {
            return true;
        }
        return false;
    }

    protected void allocateUtilityService() {
        synchronized (this.options) {
            if (this.utilityService == null) {
                this.utilityService = new UtilityService().setParent(this);
            }
        }
    }

    @Override
    public void setHost(ServiceHost serviceHost) {
        this.host = serviceHost;
    }

    @Override
    public void setSelfLink(String path) {
        this.selfLink = path;
    }

    @Override
    public void setOperationProcessingChain(OperationProcessingChain opProcessingChain) {
        this.opProcessingChain = opProcessingChain;
    }

    @Override
    public void setProcessingStage(ProcessingStage stage) {
        if (this.stage == stage) {
            return;
        }

        this.stage = stage;

        if (stage == ProcessingStage.AVAILABLE) {
            getHost().processPendingServiceAvailableOperations(this, null, false);
            getHost().getOperationTracker().processPendingServiceStartOperations(
                    getSelfLink(), ProcessingStage.AVAILABLE, this);
        }

        if (stage == ProcessingStage.STOPPED) {
            getHost().getOperationTracker().processPendingServiceStartOperations(
                    getSelfLink(), ProcessingStage.STOPPED, this);
        }
    }

    @Override
    public ServiceDocument setInitialState(Object state, Long version) {
        ServiceDocument d = Utils.fromJson(state, this.stateType);
        if (version != null) {
            d.documentVersion = version;
        }
        return d;
    }

    @Override
    public Service getUtilityService(String uriPath) {
        allocateUtilityService();
        return this.utilityService;
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ServiceDocument d;

        try {
            d = this.stateType.newInstance();
        } catch (Exception e) {
            logSevere(e);
            return null;
        }

        d.documentDescription = getHost().buildDocumentDescription(this);
        return d;
    }

    public void logSevere(Throwable e) {
        doLogging(Level.SEVERE, () -> Utils.toString(e));
    }

    public void logSevere(String fmt, Object... args) {
        doLogging(Level.SEVERE, () -> String.format(fmt, args));
    }

    public void logSevere(Supplier<String> messageSupplier) {
        doLogging(Level.SEVERE, messageSupplier);
    }

    public void logInfo(String fmt, Object... args) {
        doLogging(Level.INFO, () -> String.format(fmt, args));
    }

    public void logInfo(Supplier<String> messageSupplier) {
        doLogging(Level.INFO, messageSupplier);
    }

    public void logFine(String fmt, Object... args) {
        doLogging(Level.FINE, () -> String.format(fmt, args));
    }

    public void logFine(Supplier<String> messageSupplier) {
        doLogging(Level.FINE, messageSupplier);
    }

    public void logWarning(String fmt, Object... args) {
        doLogging(Level.WARNING, () -> String.format(fmt, args));
    }

    public void logWarning(Supplier<String> messageSupplier) {
        doLogging(Level.WARNING, messageSupplier);
    }

    public void log(Level level, String fmt, Object... args) {
        doLogging(level, () -> String.format(fmt, args));
    }

    public void log(Level level, Supplier<String> messageSupplier) {
        doLogging(level, messageSupplier);
    }

    protected void doLogging(Level level, Supplier<String> messageSupplier) {
        Logger lg = Logger.getLogger(this.getClass().getName());
        if (!lg.isLoggable(level)) {
            return;
        }
        URI uri;
        String classOrUri = this.host != null && this.selfLink != null && (uri = getUri()) != null ?
                uri.toString() :
                this.getClass().getSimpleName();
        Utils.log(lg, 3, classOrUri, level, messageSupplier);
    }

    @Override
    public void setPeerNodeSelectorPath(String uriPath) {
        throw new RuntimeException("Replication is not supported");
    }

    @Override
    public String getPeerNodeSelectorPath() {
        return null;
    }

    @Override
    public void setDocumentIndexPath(String uriPath) {
        throw new RuntimeException("Indexing is not supported");
    }

    @Override
    public String getDocumentIndexPath() {
        return null;
    }

    @Override
    public EnumSet<ServiceOption> getOptions() {
        return this.options.clone();
    }

    public void publish(Operation op) {
        UtilityService u = this.utilityService;
        if (u == null) {
            return;
        }
        u.notifySubscribers(op);
    }

    @Override
    public void setState(Operation op, ServiceDocument state) {
        op.linkState(state);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends ServiceDocument> T getState(Operation op) {
        return (T) op.getLinkedState();
    }

    @Override
    public void setMaintenanceIntervalMicros(long micros) {
        if (micros < 0) {
            throw new IllegalArgumentException("micros must be positive");
        }

        if (micros > 0 && micros < Service.MIN_MAINTENANCE_INTERVAL_MICROS) {
            logWarning("Maintenance interval %d is less than the minimum interval %d"
                    + ", reducing to min interval", micros, Service.MIN_MAINTENANCE_INTERVAL_MICROS);
            micros = Service.MIN_MAINTENANCE_INTERVAL_MICROS;
        }

        this.maintenanceIntervalMicros = micros;
        if (getHost() != null
                && getProcessingStage() == ProcessingStage.AVAILABLE
                && micros < getHost().getMaintenanceCheckIntervalMicros()) {
            getHost().scheduleServiceMaintenance(this);
        }
    }

    @Override
    public void setCacheClearDelayMicros(long micros) {
        this.cacheClearDelayMicros = micros;
    }

    @Override
    public long getMaintenanceIntervalMicros() {
        return this.maintenanceIntervalMicros;
    }

    @Override
    public long getCacheClearDelayMicros() {
        return this.cacheClearDelayMicros != null ? this.cacheClearDelayMicros :
            this.host.getServiceCacheClearDelayMicros();
    }

    @Override
    public Operation dequeueRequest() {
        return null;
    }

    @Override
    public Class<? extends ServiceDocument> getStateType() {
        return this.stateType;
    }

    @Override
    public void handleConfigurationRequest(Operation request) {
        if (request.getAction() == Action.PATCH) {
            allocateUtilityService();
            this.utilityService.handlePatchConfiguration(request, null);
            return;
        }

        if (request.getAction() != Action.GET) {
            request.fail(new IllegalArgumentException("Action not supported: "
                    + request.getAction()));
            return;
        }

        ServiceConfiguration config = Utils.buildServiceConfig(new ServiceConfiguration(), this);
        request.setBodyNoCloning(config).complete();
    }

    /**
     * Set authorization context on operation.
     */
    @Override
    public final void setAuthorizationContext(Operation op, AuthorizationContext ctx) {
        if (getHost().isPrivilegedService(this)) {
            op.setAuthorizationContext(ctx);
        } else {
            throw new RuntimeException("Service not allowed to set authorization context");
        }
    }

    /**
     * Returns the host's token signer.
     */
    public final Signer getTokenSigner() {
        if (getHost().isPrivilegedService(this)) {
            return getHost().getTokenSigner();
        } else {
            throw new RuntimeException("Service not allowed to get token signer");
        }
    }

    /**
     * Returns the host's token verifier.
     */
    public final Verifier getTokenVerifier() {
        if (getHost().isPrivilegedService(this)) {
            return getHost().getTokenVerifier();
        } else {
            throw new RuntimeException("Service not allowed to get token signer");
        }
    }

    /**
     * Returns the system user's authorization context.
     */
    @Override
    public final AuthorizationContext getSystemAuthorizationContext() {
        if (getHost().isPrivilegedService(this)) {
            return getHost().getSystemAuthorizationContext();
        } else {
            throw new RuntimeException("Service not allowed to get system authorization context");
        }
    }

    /**
     * Returns the authorization context associated with a given subject.
     */
    public final AuthorizationContext getAuthorizationContextForSubject(String subject) {
        if (getHost().isPrivilegedService(this)) {
            return getHost().getAuthorizationContextForSubject(subject);
        } else {
            throw new RuntimeException(
                    "Service not allowed to get authorization context for a subject");
        }
    }

    /**
     * @see #handleUiGet(String, Service, Operation)
     * @param get
     */
    protected void handleUiGet(Operation get) {
        handleUiGet(getSelfLink(), this, get);
    }

    /**
     * This method does basic URL rewriting and forwards to the Ui service.
     *
     * Every request to /some/service/FILE gets forwarded to
     * /user-interface/resources/${serviceClass}/FILE
     * @param get
     */
    protected void handleUiGet(String selfLink, Service ownerService, Operation get) {
        URI uri = get.getUri();
        String requestUri = uri.getPath();
        String uiResourcePath;

        ServiceDocumentDescription desc = ownerService.getDocumentTemplate().documentDescription;
        if (desc != null && desc.userInterfaceResourcePath != null) {
            uiResourcePath = UriUtils.buildUriPath(ServiceUriPaths.UI_RESOURCES,
                    desc.userInterfaceResourcePath);
        } else {
            uiResourcePath = Utils.buildUiResourceUriPrefixPath(ownerService);
        }

        if (requestUri.startsWith(uiResourcePath)) {
            Exception e = new ServiceNotFoundException(UriUtils.buildUri(uri.getScheme(), uri.getHost(),
                    uri.getPort(), uri.getPath().substring(uiResourcePath.length()), uri.getQuery()).toString());
            ServiceErrorResponse r = Utils.toServiceErrorResponse(e);
            r.statusCode = Operation.STATUS_CODE_NOT_FOUND;
            r.stackTrace = null;

            get.setStatusCode(Operation.STATUS_CODE_NOT_FOUND)
                    .setContentType(Operation.MEDIA_TYPE_APPLICATION_JSON)
                    .fail(e, r);
            return;
        }

        if (selfLink.equals(requestUri) && !UriUtils.URI_PATH_CHAR.equals(requestUri)) {
            // no trailing /, redirect to a location with trailing /
            get.setStatusCode(Operation.STATUS_CODE_MOVED_TEMP);

            String loc = selfLink + UriUtils.URI_PATH_CHAR;
            if (get.getUri().getRawQuery() != null) {
                loc += "?" + get.getUri().getRawQuery();
            }
            get.addResponseHeader(Operation.LOCATION_HEADER,  loc);
            get.complete();
            return;
        } else {
            String relativeToSelfUri = UriUtils.URI_PATH_CHAR.equals(selfLink) ?
                    requestUri : requestUri.substring(selfLink.length());
            if (relativeToSelfUri.equals(UriUtils.URI_PATH_CHAR)) {
                // serve the index.html
                uiResourcePath += UriUtils.URI_PATH_CHAR + ServiceUriPaths.UI_RESOURCE_DEFAULT_FILE;
            } else {
                // serve whatever resource
                uiResourcePath += relativeToSelfUri;
            }
        }

        // Forward request to the /user-interface service
        Operation operation = get.clone();
        operation.setUri(UriUtils.buildUri(getHost(), uiResourcePath, uri.getQuery()))
                .setCompletion((o, e) -> {
                    get.setBodyNoCloning(o.getBodyRaw())
                            .setStatusCode(o.getStatusCode())
                            .setContentType(o.getContentType());
                    if (e != null) {
                        get.fail(e);
                    } else {
                        get.complete();
                    }
                });

        getHost().sendRequest(operation);
    }

    /**
     * Records the handler invocation time for an operation if the instrumentation option is
     * set in the service.This method has to be called by the child class that extends the
     * StatelessService once operation processing starts in the handler.
     */
    public void setOperationHandlerInvokeTimeStat(Operation request) {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return;
        }
        request.setHandlerInvokeTime(System.nanoTime() / 1000);
    }

    /**
     * Updates the operation duration stat using the handler invocation time and the current time
     * if the instrumentation option is set in the service.This method has to be called by the child
     * class that extends the StatelessService once processing is completed to report the statistics
     * for operation duration.
     */
    public void setOperationDurationStat(Operation request) {
        if (!hasOption(Service.ServiceOption.INSTRUMENTATION)) {
            return;
        }
        if (request.getInstrumentationContext() == null) {
            return;
        }
        setStat(ServiceStatUtils.getPerActionDurationName(request.getAction()),
                (System.nanoTime() / 1000)
                        - request.getInstrumentationContext().handleInvokeTimeMicros);

    }
}
