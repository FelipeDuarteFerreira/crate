/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.cluster;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ack.AckedRequest;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Priority;

import io.crate.common.unit.TimeValue;
import io.crate.concurrent.CompletionListenable;

/**
 * An extension interface to {@link ClusterStateUpdateTask} that allows to be notified when
 * all the nodes have acknowledged a cluster state update request
 */
public abstract class AckedClusterStateUpdateTask<Response> extends ClusterStateUpdateTask
    implements AckedClusterStateTaskListener, CompletionListenable<Response> {

    private final AckedRequest request;
    private final CompletableFuture<Response> future;

    protected AckedClusterStateUpdateTask(AckedRequest request, ActionListener<Response> listener) {
        this(Priority.NORMAL, request);
        future.whenComplete(listener);
    }

    protected AckedClusterStateUpdateTask(Priority priority, AckedRequest request, ActionListener<Response> listener) {
        this(priority, request);
        future.whenComplete(listener);
    }

    protected AckedClusterStateUpdateTask(AckedRequest request) {
        this(Priority.NORMAL, request);
    }

    protected AckedClusterStateUpdateTask(Priority priority, AckedRequest request) {
        super(priority);
        this.future = new CompletableFuture<>();
        this.request = request;
    }

    /**
     * Called to determine which nodes the acknowledgement is expected from
     *
     * @param discoveryNode a node
     * @return true if the node is expected to send ack back, false otherwise
     */
    public boolean mustAck(DiscoveryNode discoveryNode) {
        return true;
    }

    /**
     * Called once all the nodes have acknowledged the cluster state update request. Must be
     * very lightweight execution, since it gets executed on the cluster service thread.
     *
     * @param e optional error that might have been thrown
     */
    public void onAllNodesAcked(@Nullable Exception e) {
        future.complete(newResponse(e == null));
    }

    protected abstract Response newResponse(boolean acknowledged);

    /**
     * Called once the acknowledgement timeout defined by
     * {@link AckedClusterStateUpdateTask#ackTimeout()} has expired
     */
    public void onAckTimeout() {
        future.complete(newResponse(false));
    }

    @Override
    public void onFailure(String source, Exception e) {
        future.completeExceptionally(e);
    }

    /**
     * Acknowledgement timeout, maximum time interval to wait for acknowledgements
     */
    public TimeValue ackTimeout() {
        return request.ackTimeout();
    }

    @Override
    public TimeValue timeout() {
        return request.masterNodeTimeout();
    }

    @Override
    public CompletableFuture<Response> completionFuture() {
        return future;
    }
}
