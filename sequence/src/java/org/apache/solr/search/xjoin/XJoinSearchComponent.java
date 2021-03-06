package org.apache.solr.search.xjoin;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.search.DocIterator;

/**
 * SOLR Search Component for performing an "x-join". It must be added to a request handler
 * in both the first and last component lists.
 * 
 * In prepare(), it obtains external process results (based on parameters in the SOLR query
 * URL), and (optionally) places a list of join ids in a query parameter. The join id list
 * should be used as the value to a terms query parser to create the main query or a query
 * filter.
 * 
 * In process(), it appends (selectable) attributes of the external process results to the
 * query results.
 * 
 * Note that results can be sorted or boosted by a property of external results by using
 * the associated XjoinValueSourceParser (creating a custom function which may be referenced
 * in, for example, a sort spec or a boost query).
 */
public class XJoinSearchComponent extends SearchComponent {

  // factory for creating XJoinResult objects per search
  private XJoinResultsFactory<?> factory;

  // document field on which to join with external results
  private String joinField;
  
  /**
   * Initialise the component by instantiating our factory class, and initialising
   * the join field.
   */
  @Override
  @SuppressWarnings("rawtypes")
  public void init(NamedList args) {
    super.init(args);
    
    try {
      Class<?> factoryClass = Class.forName((String)args.get(XJoinParameters.INIT_RESULTS_FACTORY));
      factory = (XJoinResultsFactory<?>)factoryClass.newInstance();
      factory.init((NamedList)args.get(XJoinParameters.EXTERNAL_PREFIX));
    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    
    joinField = (String)args.get(XJoinParameters.INIT_JOIN_FIELD);
  }
  
  // get the results factory
  /*package*/ XJoinResultsFactory<?> getResultsFactory() {
    return factory;
  }
  
  // get the context tag for XJoin results
  /*package*/ String getResultsTag() {
    return XJoinResults.class.getName() + "::" + getName();
  }
  
  /**
   * Generate external process results (if they have not already been generated).
   */
  @Override
  public void prepare(ResponseBuilder rb) throws IOException {
    SolrParams params = rb.req.getParams();
    if (! params.getBool(getName(), false)) {
      return;
    }
      
    XJoinResults<?> results = (XJoinResults<?>)rb.req.getContext().get(getResultsTag());
    if (results != null) {
      return;
    }
      
    // generate external process results, by passing 'external' prefixed parameters
    // from the query string to our factory
    String prefix = getName() + "." + XJoinParameters.EXTERNAL_PREFIX + ".";
    ModifiableSolrParams externalParams = new ModifiableSolrParams();
    for (Iterator<String> it = params.getParameterNamesIterator(); it.hasNext(); ) {
      String name = it.next();
      if (name.startsWith(prefix)) {
        externalParams.set(name.substring(prefix.length()), params.get(name));
      }
    }
    results = factory.getResults(externalParams);
    rb.req.getContext().put(getResultsTag(), results);
  }

  /**
   * Match up search results and add corresponding data for each result (if we have query
   * results available).
   */
  @Override
  @SuppressWarnings("rawtypes")
  public void process(ResponseBuilder rb) throws IOException {
      SolrParams params = rb.req.getParams();
      if (! params.getBool(getName(), false)) {
        return;
      }
      
      XJoinResults<?> results = (XJoinResults<?>)rb.req.getContext().get(getResultsTag());
      if (results == null || rb.getResults() == null) {
        return;
      }
      
      // general results
      FieldAppender appender = new FieldAppender((String)params.get(getName() + "." + XJoinParameters.RESULTS_FIELD_LIST, "*"));
      NamedList general = appender.addNamedList(rb.rsp.getValues(), getName(), results);
      
      // per doc results
      FieldAppender docAppender = new FieldAppender((String)params.get(getName() + "." + XJoinParameters.DOC_FIELD_LIST, "*"));
      Set<String> joinFields = new HashSet<>();
      joinFields.add(joinField);
      for (DocIterator it = rb.getResults().docList.iterator(); it.hasNext(); ) {
        Document doc = rb.req.getSearcher().doc(it.nextDoc(), joinFields);
        Object object = results.getResult(doc.get(joinField));
        if (object != null) {
          docAppender.addNamedList(general, "doc", object);
        }
      }
  }
  
  /*package*/ String getJoinField() {
    return joinField;
  }

  @Override
  public String getDescription() {
    return "$description$";
  }

  @Override
  public String getSource() {
    return "$source$";
  }

}
