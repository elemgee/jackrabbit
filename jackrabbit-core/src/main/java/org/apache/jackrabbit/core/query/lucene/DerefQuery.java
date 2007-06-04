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

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.HitCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Implements a lucene <code>Query</code> which returns the nodes selected by
 * a reference property of the context node.
 */
class DerefQuery extends Query {

    /**
     * The context query
     */
    private final Query contextQuery;

    /**
     * The name of the reference property.
     */
    private final String refProperty;

    /**
     * The nameTest to apply on target node, or <code>null</code> if all
     * target nodes should be selected.
     */
    private final String nameTest;

    /**
     * The scorer of the context query
     */
    private Scorer contextScorer;

    /**
     * The scorer of the name test query
     */
    private Scorer nameTestScorer;

    /**
     * Creates a new <code>DerefQuery</code> based on a <code>context</code>
     * query.
     *
     * @param context the context for this query.
     * @param refProperty the name of the reference property.
     * @param nameTest a name test or <code>null</code> if any node is
     *  selected.
     */
    DerefQuery(Query context, String refProperty, String nameTest) {
        this.contextQuery = context;
        this.refProperty = refProperty;
        this.nameTest = nameTest;
    }

    /**
     * Creates a <code>Weight</code> instance for this query.
     *
     * @param searcher the <code>Searcher</code> instance to use.
     * @return a <code>DerefWeight</code>.
     */
    protected Weight createWeight(Searcher searcher) {
        return new DerefWeight(searcher);
    }

    /**
     * Always returns 'DerefQuery'.
     *
     * @param field the name of a field.
     * @return 'DerefQuery'.
     */
    public String toString(String field) {
        return "DerefQuery";
    }

    /**
     * {@inheritDoc}
     */
    public Query rewrite(IndexReader reader) throws IOException {
        Query cQuery = contextQuery.rewrite(reader);
        if (cQuery == contextQuery) {
            return this;
        } else {
            return new DerefQuery(cQuery, refProperty, nameTest);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void extractTerms(Set terms) {
        // no terms to extract
    }

    //-------------------< DerefWeight >------------------------------------

    /**
     * The <code>Weight</code> implementation for this <code>DerefQuery</code>.
     */
    private class DerefWeight implements Weight {

        /**
         * The searcher in use
         */
        private final Searcher searcher;

        /**
         * Creates a new <code>DerefWeight</code> instance using
         * <code>searcher</code>.
         *
         * @param searcher a <code>Searcher</code> instance.
         */
        private DerefWeight(Searcher searcher) {
            this.searcher = searcher;
        }

        /**
         * Returns this <code>DerefQuery</code>.
         *
         * @return this <code>DerefQuery</code>.
         */
        public Query getQuery() {
            return DerefQuery.this;
        }

        /**
         * {@inheritDoc}
         */
        public float getValue() {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public float sumOfSquaredWeights() throws IOException {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public void normalize(float norm) {
        }

        /**
         * Creates a scorer for this <code>DerefQuery</code>.
         *
         * @param reader a reader for accessing the index.
         * @return a <code>DerefScorer</code>.
         * @throws IOException if an error occurs while reading from the index.
         */
        public Scorer scorer(IndexReader reader) throws IOException {
            contextScorer = contextQuery.weight(searcher).scorer(reader);
            if (nameTest != null) {
                nameTestScorer = new TermQuery(new Term(FieldNames.LABEL, nameTest)).weight(searcher).scorer(reader);
            }
            return new DerefScorer(searcher.getSimilarity(), reader);
        }

        /**
         * {@inheritDoc}
         */
        public Explanation explain(IndexReader reader, int doc) throws IOException {
            return new Explanation();
        }
    }

    //----------------------< DerefScorer >---------------------------------

    /**
     * Implements a <code>Scorer</code> for this <code>DerefQuery</code>.
     */
    private class DerefScorer extends Scorer {

        /**
         * An <code>IndexReader</code> to access the index.
         */
        private final IndexReader reader;

        /**
         * BitSet storing the id's of selected documents
         */
        private final BitSet hits;

        /**
         * List of UUIDs of selected nodes
         */
        private List uuids = null;

        /**
         * The next document id to return
         */
        private int nextDoc = -1;

        /**
         * Creates a new <code>DerefScorer</code>.
         *
         * @param similarity the <code>Similarity</code> instance to use.
         * @param reader     for index access.
         */
        protected DerefScorer(Similarity similarity, IndexReader reader) {
            super(similarity);
            this.reader = reader;
            this.hits = new BitSet(reader.maxDoc());
        }

        /**
         * {@inheritDoc}
         */
        public boolean next() throws IOException {
            calculateChildren();
            nextDoc = hits.nextSetBit(nextDoc + 1);
            return nextDoc > -1;
        }

        /**
         * {@inheritDoc}
         */
        public int doc() {
            return nextDoc;
        }

        /**
         * {@inheritDoc}
         */
        public float score() throws IOException {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public boolean skipTo(int target) throws IOException {
            calculateChildren();
            nextDoc = hits.nextSetBit(target);
            return nextDoc > -1;
        }

        /**
         * {@inheritDoc}
         *
         * @throws UnsupportedOperationException this implementation always
         *                                       throws an <code>UnsupportedOperationException</code>.
         */
        public Explanation explain(int doc) throws IOException {
            throw new UnsupportedOperationException();
        }

        private void calculateChildren() throws IOException {
            if (uuids == null) {
                uuids = new ArrayList();
                contextScorer.score(new HitCollector() {
                    public void collect(int doc, float score) {
                        hits.set(doc);
                    }
                });

                // collect nameTest hits
                final BitSet nameTestHits = new BitSet();
                if (nameTestScorer != null) {
                    nameTestScorer.score(new HitCollector() {
                        public void collect(int doc, float score) {
                            nameTestHits.set(doc);
                        }
                    });
                }

                // retrieve uuids of target nodes
                String prefix = FieldNames.createNamedValue(refProperty, "");
                for (int i = hits.nextSetBit(0); i >= 0; i = hits.nextSetBit(i + 1)) {
                    String[] values = reader.document(i).getValues(FieldNames.PROPERTIES);
                    if (values == null) {
                        // no reference properties at all on this node
                        continue;
                    }
                    for (int v = 0; v < values.length; v++) {
                        if (values[v].startsWith(prefix)) {
                            uuids.add(values[v].substring(prefix.length()));
                        }
                    }
                }

                // collect the doc ids of all target nodes. we reuse the existing
                // bitset.
                hits.clear();
                for (Iterator it = uuids.iterator(); it.hasNext();) {
                    TermDocs node = reader.termDocs(new Term(FieldNames.UUID, (String) it.next()));
                    try {
                        while (node.next()) {
                            hits.set(node.doc());
                        }
                    } finally {
                        node.close();
                    }
                }
                // filter out the target nodes that do not match the name test
                // if there is any name test at all.
                if (nameTestScorer != null) {
                    hits.and(nameTestHits);
                }
            }
        }
    }
}
