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

package org.elasticsearch.index.search;

import static org.elasticsearch.common.lucene.search.Queries.newLenientFieldQuery;
import static org.elasticsearch.common.lucene.search.Queries.newUnmappedFieldQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.DisableGraphAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.ExtendedCommonTermsQuery;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SynonymQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.QueryBuilder;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.search.MultiPhrasePrefixQuery;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.query.support.QueryParsers;

public class MatchQuery {

    private static final DeprecationLogger DEPRECATION_LOGGER = new DeprecationLogger(LogManager.getLogger(MappedFieldType.class));
    static final float DEFAULT_BOOST = 1.0f;

    public enum Type {
        /**
         * The text is analyzed and terms are added to a boolean query.
         */
        BOOLEAN(0),
        /**
         * The text is analyzed and used as a phrase query.
         */
        PHRASE(1),
        /**
         * The text is analyzed and used in a phrase query, with the last term acting as a prefix.
         */
        PHRASE_PREFIX(2);

        private final int ordinal;

        Type(int ordinal) {
            this.ordinal = ordinal;
        }
    }

    public enum ZeroTermsQuery {
        NONE(0),
        ALL(1),
        // this is used internally to make sure that query_string and simple_query_string
        // ignores query part that removes all tokens.
        NULL(2);

        private final int ordinal;

        ZeroTermsQuery(int ordinal) {
            this.ordinal = ordinal;
        }
    }

    /**
     * the default phrase slop
     */
    public static final int DEFAULT_PHRASE_SLOP = 0;

    /**
     * the default leniency setting
     */
    public static final boolean DEFAULT_LENIENCY = false;

    /**
     * the default zero terms query
     */
    public static final ZeroTermsQuery DEFAULT_ZERO_TERMS_QUERY = ZeroTermsQuery.NONE;

    protected final QueryShardContext context;

    protected Analyzer analyzer;

    protected BooleanClause.Occur occur = BooleanClause.Occur.SHOULD;

    protected boolean enablePositionIncrements = true;

    protected int phraseSlop = DEFAULT_PHRASE_SLOP;

    protected Fuzziness fuzziness = null;

    protected int fuzzyPrefixLength = FuzzyQuery.defaultPrefixLength;

    protected int maxExpansions = FuzzyQuery.defaultMaxExpansions;

    protected boolean transpositions = FuzzyQuery.defaultTranspositions;

    protected MultiTermQuery.RewriteMethod fuzzyRewriteMethod;

    protected boolean lenient = DEFAULT_LENIENCY;

    protected ZeroTermsQuery zeroTermsQuery = DEFAULT_ZERO_TERMS_QUERY;

    protected Float commonTermsCutoff = null;

    protected boolean autoGenerateSynonymsPhraseQuery = true;

    public MatchQuery(QueryShardContext context) {
        this.context = context;
    }

    public void setAnalyzer(String analyzerName) {
        this.analyzer = context.getMapperService().getIndexAnalyzers().get(analyzerName);
        if (analyzer == null) {
            throw new IllegalArgumentException("No analyzer found for [" + analyzerName + "]");
        }
    }

    public void setAnalyzer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    public void setOccur(BooleanClause.Occur occur) {
        this.occur = occur;
    }

    public void setCommonTermsCutoff(Float cutoff) {
        this.commonTermsCutoff = cutoff;
    }

    public void setEnablePositionIncrements(boolean enablePositionIncrements) {
        this.enablePositionIncrements = enablePositionIncrements;
    }

    public void setPhraseSlop(int phraseSlop) {
        this.phraseSlop = phraseSlop;
    }

    public void setFuzziness(Fuzziness fuzziness) {
        this.fuzziness = fuzziness;
    }

    public void setFuzzyPrefixLength(int fuzzyPrefixLength) {
        this.fuzzyPrefixLength = fuzzyPrefixLength;
    }

    public void setMaxExpansions(int maxExpansions) {
        this.maxExpansions = maxExpansions;
    }

    public void setTranspositions(boolean transpositions) {
        this.transpositions = transpositions;
    }

    public void setFuzzyRewriteMethod(MultiTermQuery.RewriteMethod fuzzyRewriteMethod) {
        this.fuzzyRewriteMethod = fuzzyRewriteMethod;
    }

    public void setLenient(boolean lenient) {
        this.lenient = lenient;
    }

    public void setZeroTermsQuery(ZeroTermsQuery zeroTermsQuery) {
        this.zeroTermsQuery = zeroTermsQuery;
    }

    public void setAutoGenerateSynonymsPhraseQuery(boolean enabled) {
        this.autoGenerateSynonymsPhraseQuery = enabled;
    }

    protected Analyzer getAnalyzer(MappedFieldType fieldType, boolean quoted) {
        if (analyzer == null) {
            return quoted ? context.getSearchQuoteAnalyzer(fieldType) : context.getSearchAnalyzer(fieldType);
        } else {
            return analyzer;
        }
    }

    public Query parse(Type type, String fieldName, Object value) {
        MappedFieldType fieldType = context.fieldMapper(fieldName);
        if (fieldType == null) {
            return newUnmappedFieldQuery(fieldName);
        }
        final String field = fieldType.name();

        Analyzer analyzer = getAnalyzer(fieldType, type == Type.PHRASE);
        assert analyzer != null;

        /*
         * If a keyword analyzer is used, we know that further analysis isn't
         * needed and can immediately return a term query.
         */
        if (analyzer == Lucene.KEYWORD_ANALYZER) {
            return blendTermQuery(new Term(fieldName, value.toString()), fieldType);
        }

        MatchQueryBuilder builder = new MatchQueryBuilder(analyzer, fieldType);
        builder.setEnablePositionIncrements(this.enablePositionIncrements);
        if (fieldType.hasPositions()) {
            builder.setAutoGenerateMultiTermSynonymsPhraseQuery(this.autoGenerateSynonymsPhraseQuery);
        } else {
            builder.setAutoGenerateMultiTermSynonymsPhraseQuery(false);
        }

        Query query = null;
        switch (type) {
            case BOOLEAN:
                if (commonTermsCutoff == null) {
                    query = builder.createBooleanQuery(field, value.toString(), occur);
                } else {
                    query = builder.createCommonTermsQuery(field, value.toString(), occur, occur, commonTermsCutoff);
                }
                break;
            case PHRASE:
                query = builder.createPhraseQuery(field, value.toString(), phraseSlop);
                break;
            case PHRASE_PREFIX:
                query = builder.createPhrasePrefixQuery(field, value.toString(), phraseSlop, maxExpansions);
                break;
            default:
                throw new IllegalStateException("No type found for [" + type + "]");
        }

        if (query == null) {
            return zeroTermsQuery();
        } else {
            return query;
        }
    }

    protected final Query termQuery(MappedFieldType fieldType, BytesRef value, boolean lenient) {
        try {
            return new TermQuery(new Term(fieldType.name(), value));
        } catch (RuntimeException e) {
            if (lenient) {
                return newLenientFieldQuery(fieldType.name(), e);
            }
            throw e;
        }
    }

    protected Query zeroTermsQuery() {
        switch (zeroTermsQuery) {
            case NULL:
                return null;
            case NONE:
                return Queries.newMatchNoDocsQuery("Matching no documents because no terms present");
            case ALL:
                return Queries.newMatchAllQuery();
            default:
                throw new IllegalStateException("unknown zeroTermsQuery " + zeroTermsQuery);
        }
    }

    private class MatchQueryBuilder extends QueryBuilder {

        private final MappedFieldType mapper;

        /**
         * Creates a new QueryBuilder using the given analyzer.
         */
        MatchQueryBuilder(Analyzer analyzer, MappedFieldType mapper) {
            super(analyzer);
            this.mapper = mapper;
        }

        @Override
        protected Query newTermQuery(Term term, float boost) {
            return blendTermQuery(term, mapper);
        }

        @Override
        protected Query analyzePhrase(String field, TokenStream stream, int slop) throws IOException {
            try {
                checkForPositions(field);
                Query query = phraseQuery(field, stream, slop, enablePositionIncrements);
                if (query instanceof PhraseQuery) {
                    // synonyms that expand to multiple terms can return a phrase query.
                    return blendPhraseQuery((PhraseQuery) query, mapper);
                }
                return query;
            } catch (IllegalStateException e) {
                if (lenient) {
                    return newLenientFieldQuery(field, e);
                }
                throw e;
            } catch (IllegalArgumentException e) {
                if (lenient == false) {
                    DEPRECATION_LOGGER.deprecatedAndMaybeLog("match_query_analyze_phrase_not_lenient", e.getMessage());
                }
                return newLenientFieldQuery(field, e);
            }
        }

        @Override
        protected Query analyzeMultiPhrase(String field, TokenStream stream, int slop) throws IOException {
            try {
                checkForPositions(field);
                return multiPhraseQuery(field, stream, slop, enablePositionIncrements);
            } catch (IllegalStateException e) {
                if (lenient) {
                    return newLenientFieldQuery(field, e);
                }
                throw e;
            } catch (IllegalArgumentException e) {
                if (lenient == false) {
                    DEPRECATION_LOGGER.deprecatedAndMaybeLog("match_query_analyze_multiphrase_not_lenient", e.getMessage());
                }
                return newLenientFieldQuery(field, e);
            }
        }

        private void checkForPositions(String field) {
            if (mapper.hasPositions() == false) {
                throw new IllegalStateException("field:[" + field + "] was indexed without position data; cannot run PhraseQuery");
            }
        }

        /**
         * Checks if graph analysis should be enabled for the field depending
         * on the provided {@link Analyzer}
         */
        protected Query createFieldQuery(Analyzer analyzer, BooleanClause.Occur operator, String field,
                                         String queryText, boolean quoted, int phraseSlop) {
            assert operator == BooleanClause.Occur.SHOULD || operator == BooleanClause.Occur.MUST;

            // Use the analyzer to get all the tokens, and then build an appropriate
            // query based on the analysis chain.
            try (TokenStream source = analyzer.tokenStream(field, queryText)) {
                if (source.hasAttribute(DisableGraphAttribute.class)) {
                    /*
                      A {@link TokenFilter} in this {@link TokenStream} disabled the graph analysis to avoid
                      paths explosion. See {@link org.elasticsearch.index.analysis.ShingleTokenFilterFactory} for details.
                     */
                    setEnableGraphQueries(false);
                }
                Query query = super.createFieldQuery(source, operator, field, quoted, phraseSlop);
                setEnableGraphQueries(true);
                return query;
            } catch (IOException e) {
                throw new RuntimeException("Error analyzing query text", e);
            }
        }

        public Query createPhrasePrefixQuery(String field, String queryText, int phraseSlop, int maxExpansions) {
            final Query query = createFieldQuery(getAnalyzer(), Occur.MUST, field, queryText, true, phraseSlop);
            return toMultiPhrasePrefix(query, phraseSlop, maxExpansions);
        }

        private Query toMultiPhrasePrefix(final Query query, int phraseSlop, int maxExpansions) {
            float boost = 1;
            Query innerQuery = query;
            while (innerQuery instanceof BoostQuery) {
                BoostQuery bq = (BoostQuery) innerQuery;
                boost *= bq.getBoost();
                innerQuery = bq.getQuery();
            }
            if (query instanceof SpanQuery) {
                return toSpanQueryPrefix((SpanQuery) query, boost);
            }
            final MultiPhrasePrefixQuery prefixQuery = new MultiPhrasePrefixQuery();
            prefixQuery.setMaxExpansions(maxExpansions);
            prefixQuery.setSlop(phraseSlop);
            if (innerQuery instanceof PhraseQuery) {
                PhraseQuery pq = (PhraseQuery) innerQuery;
                Term[] terms = pq.getTerms();
                int[] positions = pq.getPositions();
                for (int i = 0; i < terms.length; i++) {
                    prefixQuery.add(new Term[]{terms[i]}, positions[i]);
                }
                return boost == 1 ? prefixQuery : new BoostQuery(prefixQuery, boost);
            } else if (innerQuery instanceof MultiPhraseQuery) {
                MultiPhraseQuery pq = (MultiPhraseQuery) innerQuery;
                Term[][] terms = pq.getTermArrays();
                int[] positions = pq.getPositions();
                for (int i = 0; i < terms.length; i++) {
                    prefixQuery.add(terms[i], positions[i]);
                }
                return boost == 1 ? prefixQuery : new BoostQuery(prefixQuery, boost);
            } else if (innerQuery instanceof TermQuery) {
                prefixQuery.add(((TermQuery) innerQuery).getTerm());
                return boost == 1 ? prefixQuery : new BoostQuery(prefixQuery, boost);
            }
            return query;
        }

        private Query toSpanQueryPrefix(SpanQuery query, float boost) {
            if (query instanceof SpanTermQuery) {
                SpanMultiTermQueryWrapper<PrefixQuery> ret =
                    new SpanMultiTermQueryWrapper<>(new PrefixQuery(((SpanTermQuery) query).getTerm()));
                return boost == 1 ? ret : new BoostQuery(ret, boost);
            } else if (query instanceof SpanNearQuery) {
                SpanNearQuery spanNearQuery = (SpanNearQuery) query;
                SpanQuery[] clauses = spanNearQuery.getClauses();
                if (clauses[clauses.length - 1] instanceof SpanTermQuery) {
                    clauses[clauses.length - 1] = new SpanMultiTermQueryWrapper<>(
                        new PrefixQuery(((SpanTermQuery) clauses[clauses.length - 1]).getTerm())
                    );
                }
                SpanNearQuery newQuery = new SpanNearQuery(clauses, spanNearQuery.getSlop(), spanNearQuery.isInOrder());
                return boost == 1 ? newQuery : new BoostQuery(newQuery, boost);
            } else if (query instanceof SpanOrQuery) {
                SpanOrQuery orQuery = (SpanOrQuery) query;
                SpanQuery[] clauses = new SpanQuery[orQuery.getClauses().length];
                for (int i = 0; i < clauses.length; i++) {
                    clauses[i] = (SpanQuery) toSpanQueryPrefix(orQuery.getClauses()[i], 1);
                }
                return boost == 1 ? new SpanOrQuery(clauses) : new BoostQuery(new SpanOrQuery(clauses), boost);
            } else {
                return query;
            }
        }

        public Query createCommonTermsQuery(String field, String queryText, Occur highFreqOccur, Occur lowFreqOccur, float
            maxTermFrequency) {
            Query booleanQuery = createBooleanQuery(field, queryText, lowFreqOccur);
            if (booleanQuery instanceof BooleanQuery) {
                BooleanQuery bq = (BooleanQuery) booleanQuery;
                return boolToExtendedCommonTermsQuery(bq, highFreqOccur, lowFreqOccur, maxTermFrequency);
            }
            return booleanQuery;
        }

        private Query boolToExtendedCommonTermsQuery(BooleanQuery bq,
                                                     Occur highFreqOccur,
                                                     Occur lowFreqOccur,
                                                     float maxTermFrequency) {
            ExtendedCommonTermsQuery query = new ExtendedCommonTermsQuery(highFreqOccur, lowFreqOccur, maxTermFrequency);
            for (BooleanClause clause : bq.clauses()) {
                if ((clause.getQuery() instanceof TermQuery) == false) {
                    return bq;
                }
                query.add(((TermQuery) clause.getQuery()).getTerm());
            }
            return query;
        }
    }

    /**
     * Called when a phrase query is built with {@link QueryBuilder#analyzePhrase(String, TokenStream, int)}
     * Subclass can override this function to blend this query to multiple fields.
     */
    protected Query blendPhraseQuery(PhraseQuery query, MappedFieldType fieldType) {
        return query;
    }

    protected Query blendTermsQuery(Term[] terms, MappedFieldType fieldType) {
        var builder = new SynonymQuery.Builder(fieldType.name());
        for (var term : terms) {
            builder.addTerm(term);
        }
        return builder.build();
    }

    protected Query blendTermQuery(Term term, MappedFieldType fieldType) {
        if (fuzziness != null) {
            try {
                fieldType.failIfNotIndexed();
                Query query = new FuzzyQuery(term, fuzziness.asDistance(term.text()), fuzzyPrefixLength, maxExpansions, transpositions);
                if (query instanceof FuzzyQuery) {
                    QueryParsers.setRewriteMethod((FuzzyQuery) query, fuzzyRewriteMethod);
                }
                return query;
            } catch (RuntimeException e) {
                if (lenient) {
                    return newLenientFieldQuery(fieldType.name(), e);
                } else {
                    throw e;
                }
            }
        }
        return termQuery(fieldType, term.bytes(), lenient);
    }

    private static Query multiPhraseQuery(String field, TokenStream stream, int slop, boolean enablePositionIncrements) throws IOException {
        MultiPhraseQuery.Builder mpqb = new MultiPhraseQuery.Builder();
        mpqb.setSlop(slop);

        TermToBytesRefAttribute termAtt = stream.getAttribute(TermToBytesRefAttribute.class);

        PositionIncrementAttribute posIncrAtt = stream.getAttribute(PositionIncrementAttribute.class);
        int position = -1;

        List<Term> multiTerms = new ArrayList<>();
        stream.reset();
        while (stream.incrementToken()) {
            int positionIncrement = posIncrAtt.getPositionIncrement();

            if (positionIncrement > 0 && multiTerms.size() > 0) {
                if (enablePositionIncrements) {
                    mpqb.add(multiTerms.toArray(new Term[0]), position);
                } else {
                    mpqb.add(multiTerms.toArray(new Term[0]));
                }
                multiTerms.clear();
            }
            position += positionIncrement;
            multiTerms.add(new Term(field, termAtt.getBytesRef()));
        }

        if (enablePositionIncrements) {
            mpqb.add(multiTerms.toArray(new Term[0]), position);
        } else {
            mpqb.add(multiTerms.toArray(new Term[0]));
        }
        return mpqb.build();
    }

    private static Query phraseQuery(String field, TokenStream stream, int slop, boolean enablePosIncrements) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        builder.setSlop(slop);

        TermToBytesRefAttribute termAtt = stream.getAttribute(TermToBytesRefAttribute.class);
        PositionIncrementAttribute posIncrAtt = stream.getAttribute(PositionIncrementAttribute.class);
        int position = -1;

        stream.reset();
        while (stream.incrementToken()) {
            if (enablePosIncrements) {
                position += posIncrAtt.getPositionIncrement();
            } else {
                position += 1;
            }
            builder.add(new Term(field, termAtt.getBytesRef()), position);
        }

        return builder.build();
    }
}
