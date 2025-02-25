/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package org.elasticsearch.index.shard;

import static org.elasticsearch.repositories.RepositoryData.EMPTY_REPO_GEN;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.lucene.index.IndexCommit;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.RepositoryMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.component.LifecycleListener;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.snapshots.IndexShardSnapshotStatus;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryData;
import org.elasticsearch.repositories.ShardGenerations;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.snapshots.SnapshotShardFailure;

import io.crate.common.collections.Tuple;

public abstract class RestoreOnlyRepository implements Repository {

    private final String indexName;

    public RestoreOnlyRepository(String indexName) {
        this.indexName = indexName;
    }

    @Override
    public RepositoryMetadata getMetadata() {
        return null;
    }

    @Override
    public void getSnapshotInfo(SnapshotId snapshotId, ActionListener<SnapshotInfo> listener) {
        listener.onResponse(null);
    }

    @Override
    public void getSnapshotGlobalMetadata(SnapshotId snapshotId,
                                          ActionListener<Metadata> listener) {

    }

    @Override
    public void getSnapshotIndexMetadata(SnapshotId snapshotId,
                                         IndexId indexId,
                                         ActionListener<IndexMetadata> listener) throws IOException {

    }

    @Override
    public void getSnapshotIndexMetadata(SnapshotId snapshotId,
                                         Collection<IndexId> indexIds,
                                         ActionListener<Collection<IndexMetadata>> listener) {

    }

    @Override
    public void getRepositoryData(ActionListener<RepositoryData> listener) {
        final IndexId indexId = new IndexId(indexName, "blah");
            listener.onResponse(new RepositoryData(EMPTY_REPO_GEN, Map.of(), Map.of(), Map.of(),
            Map.of(indexId, List.of()), ShardGenerations.EMPTY));
    }

    @Override
    public void finalizeSnapshot(SnapshotId snapshotId,
                                 ShardGenerations shardGenerations,
                                 long startTime,
                                 String failure,
                                 int totalShards,
                                 List<SnapshotShardFailure> shardFailures,
                                 long repositoryStateId,
                                 boolean includeGlobalState,
                                 Metadata clusterMetadata,
                                 Version repositoryMetaVersion,
                                 Function<ClusterState, ClusterState> stateTransformer,
                                 ActionListener<Tuple<RepositoryData, SnapshotInfo>> listener) {
        listener.onResponse(null);
    }

    @Override
    public void deleteSnapshots(Collection<SnapshotId> snapshotIds,
                               long repositoryStateId,
                               Version repositoryMetaVersion,
                               ActionListener<Void> listener) {

    }

    @Override
    public String startVerification() {
        return null;
    }

    @Override
    public void endVerification(String verificationToken) {

    }

    @Override
    public void verify(String verificationToken, DiscoveryNode localNode) {

    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public void snapshotShard(Store store,
                              MapperService mapperService,
                              SnapshotId snapshotId,
                              IndexId indexId,
                              IndexCommit snapshotIndexCommit,
                              @Nullable String shardStateIdentifier,
                              IndexShardSnapshotStatus snapshotStatus,
                              Version repositoryMetaVersion,
                              ActionListener<String> listener) {

    }

    @Override
    public void restoreShard(Store store,
                             SnapshotId snapshotId,
                             IndexId indexId,
                             ShardId snapshotShardId,
                             RecoveryState recoveryState,
                             ActionListener<Void> listener) {

    }

    @Override
    public Lifecycle.State lifecycleState() {
        return null;
    }

    @Override
    public void addLifecycleListener(LifecycleListener listener) {

    }

    @Override
    public void removeLifecycleListener(LifecycleListener listener) {

    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void close() {

    }
}
