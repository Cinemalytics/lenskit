/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.knn.user;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.*;
import org.grouplens.grapht.annotation.DefaultProvider;
import org.grouplens.lenskit.transform.normalize.UserVectorNormalizer;
import org.grouplens.lenskit.vectors.ImmutableSparseVector;
import org.grouplens.lenskit.vectors.MutableSparseVector;
import org.lenskit.data.ratings.RatingVectorPDAO;
import org.lenskit.inject.Shareable;
import org.lenskit.inject.Transient;
import org.lenskit.util.IdBox;
import org.lenskit.util.collections.LongUtils;
import org.lenskit.util.io.ObjectStream;
import org.lenskit.util.keys.SortedKeyIndex;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Provider;
import java.io.Serializable;
import java.util.List;

/**
 * User snapshot used by {@link SnapshotNeighborFinder}.
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 * @since 2.1
 */
@Shareable
@ThreadSafe
@DefaultProvider(UserSnapshot.Builder.class)
public class UserSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;
    private final SortedKeyIndex users;
    private final List<ImmutableSparseVector> vectors;
    private final List<ImmutableSparseVector> normedVectors;
    private final Long2ObjectMap<LongSortedSet> itemUserSets;

    /**
     * Construct a user snapshot.
     * @param us The set of users.
     * @param vs The list of raw user vectors.
     * @param nvs The list of normalized user vectors.
     */
    UserSnapshot(SortedKeyIndex us, List<ImmutableSparseVector> vs, List<ImmutableSparseVector> nvs,
                 Long2ObjectMap<LongSortedSet> iuSets) {
        Preconditions.checkArgument(vs.size() == us.size(),
                                    "incorrectly sized vector list");
        Preconditions.checkArgument(nvs.size() == us.size(),
                                    "incorrectly sized normalized vector list");
        users = us;
        vectors = ImmutableList.copyOf(vs);
        normedVectors = ImmutableList.copyOf(nvs);
        itemUserSets = iuSets;
    }

    public ImmutableSparseVector getUserVector(long user) {
        int idx = users.tryGetIndex(user);
        Preconditions.checkArgument(idx >= 0, "invalid user " + user);
        return vectors.get(idx);
    }

    public ImmutableSparseVector getNormalizedUserVector(long user) {
        int idx = users.tryGetIndex(user);
        Preconditions.checkArgument(idx >= 0, "invalid user " + user);
        return normedVectors.get(idx);
    }

    public LongSet getItemUsers(long item) {
        return itemUserSets.get(item);
    }

    public static class Builder implements Provider<UserSnapshot> {
        private final RatingVectorPDAO rvDAO;
        private final UserVectorNormalizer normalizer;


        @Inject
        public Builder(@Transient RatingVectorPDAO rvd,
                       @Transient UserVectorNormalizer norm) {
            rvDAO = rvd;
            normalizer = norm;
        }

        @Override
        public UserSnapshot get() {
            Long2ObjectMap<MutableSparseVector> vectors = new Long2ObjectOpenHashMap<>();
            try (ObjectStream<IdBox<Long2DoubleMap>> users = rvDAO.streamUsers()) {
                for (IdBox<Long2DoubleMap> user : users) {
                    MutableSparseVector uvec = MutableSparseVector.create(user.getValue());
                    vectors.put(user.getId(), uvec);
                }
            }

            Long2ObjectMap<LongList> itemUserLists = new Long2ObjectOpenHashMap<>();
            SortedKeyIndex domain = SortedKeyIndex.fromCollection(vectors.keySet());
            ImmutableList.Builder<ImmutableSparseVector> vecs = ImmutableList.builder();
            ImmutableList.Builder<ImmutableSparseVector> nvecs = ImmutableList.builder();
            for (LongIterator uiter = domain.keyIterator(); uiter.hasNext();) {
                final long user = uiter.nextLong();
                MutableSparseVector vec = vectors.get(user);
                // save user's original vector
                ImmutableSparseVector userVector = vec.immutable();
                vecs.add(userVector);
                // normalize user vector
                normalizer.normalize(user, userVector, vec);
                // and save normalized vector
                nvecs.add(vec.immutable());
                for (LongIterator iiter = userVector.keySet().iterator(); iiter.hasNext();) {
                    final long item = iiter.nextLong();
                    LongList itemUsers = itemUserLists.get(item);
                    if (itemUsers == null) {
                        itemUsers = new LongArrayList();
                        itemUserLists.put(item, itemUsers);
                    }
                    itemUsers.add(user);
                }
            }

            Long2ObjectMap<LongSortedSet> itemUserSets = new Long2ObjectOpenHashMap<>();
            for (Long2ObjectMap.Entry<LongList> entry: itemUserLists.long2ObjectEntrySet()) {
                itemUserSets.put(entry.getLongKey(), LongUtils.packedSet(entry.getValue()));
            }
            return new UserSnapshot(domain, vecs.build(), nvecs.build(), itemUserSets);
        }
    }
}
