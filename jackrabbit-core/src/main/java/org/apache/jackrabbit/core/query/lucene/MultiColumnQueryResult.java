/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.query.lucene;

import java.io.IOException;

import javax.jcr.RepositoryException;

import org.apache.jackrabbit.core.ItemManager;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.jackrabbit.spi.Path;
import org.apache.jackrabbit.spi.commons.query.qom.ColumnImpl;

/**
 * <code>MultiColumnQueryResult</code> implements a query result that executes
 * a {@link MultiColumnQuery}.
 */
public class MultiColumnQueryResult extends QueryResultImpl {

    /**
     * The query to execute.
     */
    private final MultiColumnQuery query;

    public MultiColumnQueryResult(SearchIndex index,
                                  ItemManager itemMgr,
                                  SessionImpl session,
                                  AccessManager accessMgr,
                                  AbstractQueryImpl queryImpl,
                                  MultiColumnQuery query,
                                  SpellSuggestion spellSuggestion,
                                  ColumnImpl[] columns,
                                  Path[] orderProps,
                                  boolean[] orderSpecs,
                                  boolean documentOrder,
                                  long offset,
                                  long limit) throws RepositoryException {
        super(index, itemMgr, session, accessMgr, queryImpl, spellSuggestion,
                columns, orderProps, orderSpecs, documentOrder, offset, limit);
        this.query = query;
        // if document order is requested get all results right away
        getResults(docOrder ? Integer.MAX_VALUE : index.getResultFetchSize());
    }

    /**
     * {@inheritDoc}
     */
    protected MultiColumnQueryHits executeQuery(long resultFetchHint)
            throws IOException {
        return index.executeQuery(session, query, orderProps,
                orderSpecs, resultFetchHint);
    }

    /**
     * {@inheritDoc}
     */
    protected ExcerptProvider createExcerptProvider() throws IOException {
        // TODO
        return null;
    }
}
