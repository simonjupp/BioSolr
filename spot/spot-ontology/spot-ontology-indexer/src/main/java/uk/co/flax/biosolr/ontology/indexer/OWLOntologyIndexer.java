/**
 * Copyright (c) 2015 Lemur Consulting Ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.flax.biosolr.ontology.indexer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.search.Searcher;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.flax.biosolr.ontology.api.OntologyEntryBean;
import uk.co.flax.biosolr.ontology.config.OntologyConfiguration;
import uk.co.flax.biosolr.ontology.indexer.visitors.RestrictionVisitor;
import uk.co.flax.biosolr.ontology.plugins.PluginException;
import uk.co.flax.biosolr.ontology.plugins.PluginManager;
import uk.co.flax.biosolr.ontology.storage.StorageEngine;
import uk.co.flax.biosolr.ontology.storage.StorageEngineException;

/**
 * Class to handle indexing a single OWL ontology file.
 * 
 * @author Matt Pearce
 */
public class OWLOntologyIndexer implements OntologyIndexer {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(OWLOntologyIndexer.class);
	
	private static final int BATCH_SIZE = 1000;
	
	private static final String RELATION_FIELD_SUFFIX = "_rel";
	
	private final String sourceKey;
	private final OntologyConfiguration config;
	private final StorageEngine storageEngine;
	private final PluginManager pluginManager;
	
	private final OWLOntology ontology;
	private final OWLReasoner reasoner;
	private final ShortFormProvider shortFormProvider;
	
	private final List<URI> ignoreUris;
	private final IRI owlNothingIRI;
	private final RestrictionVisitor restrictionVisitor;
	
	private Map<IRI, Set<String>> labels = new HashMap<>();
	
	public OWLOntologyIndexer(String source, OntologyConfiguration config, StorageEngine storageEngine, PluginManager pluginManager)
			throws OntologyIndexingException {
		this.sourceKey = source;
		this.config = config;
		this.storageEngine = storageEngine;
		this.pluginManager = pluginManager;
		
		this.ignoreUris = buildIgnoreUriList();
        
		try {
			this.ontology = loadOntology();
			this.reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
			this.shortFormProvider = new SimpleShortFormProvider();
			this.owlNothingIRI = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLNothing().getIRI();
			this.restrictionVisitor = new RestrictionVisitor(Collections.singleton(ontology));
		} catch (OWLOntologyCreationException e) {
			throw new OntologyIndexingException(e);
		}
	}
	
	private OWLOntology loadOntology() throws OWLOntologyCreationException {
        LOGGER.info("Loading ontology from {}...", config.getAccessURI());
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI iri = IRI.create(config.getAccessURI());
        return manager.loadOntologyFromOntologyDocument(iri);
	}
	
	private List<URI> buildIgnoreUriList() {
		List<URI> ignoreUris = new ArrayList<>();
		
		if (config.getIgnoreURIs() != null) {
			for (String uriString : config.getIgnoreURIs()) {
				try {
					URI uri = new URI(uriString);
					ignoreUris.add(uri);
				} catch (URISyntaxException e) {
					LOGGER.error("URI syntax exception: {}", e.getMessage());
				}
			}
		}
		
		return ignoreUris;
	}

	@Override
	public void indexOntology() throws OntologyIndexingException {
		List<OntologyEntryBean> entries = new ArrayList<>(BATCH_SIZE);

		try {
			int count = 0;
			
			for (OWLClass owlClass : ontology.getClassesInSignature()) {
				if (!shouldIgnore(owlClass)) {
					OntologyEntryBean entry = buildOntologyEntry(owlClass);
					entries.add(entry);

					if (entries.size() == BATCH_SIZE) {
						storageEngine.storeOntologyEntries(entries);
						count += entries.size();
						LOGGER.info("Indexed {} entries", count);
						entries.clear();
					}
				}
			}
			
			// Index the final batch
			storageEngine.storeOntologyEntries(entries);
			count += entries.size();
			LOGGER.info("Indexed {} entries", count);
		} catch (StorageEngineException e) {
			LOGGER.error("Caught storage engine exception: {}", e.getMessage());
		}
		
		LOGGER.info("Indexing complete");
	}
	
	private OntologyEntryBean buildOntologyEntry(OWLClass owlClass) {
		OntologyEntryBean bean = new OntologyEntryBean();
		
		bean.setSource(sourceKey);
        bean.setUri(owlClass.getIRI().toString());
        bean.setId(sourceKey + "_" + bean.getUri());
        bean.setShortForm(shortFormProvider.getShortForm(owlClass));
        bean.setLabel(findLabels(owlClass.getIRI()));
        bean.setSynonym(findLabelsByAnnotationURI(owlClass, config.getSynonymAnnotationURI()));
        bean.setDescription(findLabelsByAnnotationURI(owlClass, config.getDefinitionAnnotationURI()));
        bean.setChildUris(new ArrayList<>(getSubClassUris(owlClass, true)));
        bean.setParentUris(new ArrayList<>(getSuperClassUris(owlClass, true)));
        bean.setDescendentUris(new ArrayList<>(getSubClassUris(owlClass, false)));
        bean.setAncestorUris(new ArrayList<>(getSuperClassUris(owlClass, false)));
		
        // Look up restrictions
        Map<String, List<String>> relatedItems = getRestrictions(owlClass);
        bean.setRelations(relatedItems);
        
        // Handle plugins
        try {
			pluginManager.processOntologyEntryPlugins(bean, sourceKey, config);
		} catch (PluginException e) {
			LOGGER.error("Plugin exception processing ontology entry {}: {}", bean.getUri(), e.getMessage());
		}
        
		return bean;
	}
	
	private boolean shouldIgnore(OWLClass owlClass) {
		boolean ret = false;
		
		if (config.getIgnoreURIs() != null) {
			for (URI uri : ignoreUris) {
				if (isChildOf(owlClass, uri)) {
					ret = true;
					break;
				}
			}
		}
		
		return ret;
	}

	/**
	 * Check if a class has a particular ancestor class.
	 * @param owlClass the class to check.
	 * @param parentUri the URI of the ancestor to look for.
	 * @return <code>true</code> if the class is a child of the parent URI.
	 */
    private boolean isChildOf(OWLClass owlClass, URI parentUri) {
    	boolean ret = false;
    	
    	// Loop through all parent classes of the class, looking for the parent URI
        NodeSet<OWLClass> superclasses = reasoner.getSuperClasses(owlClass, false);
        for (OWLClassExpression oce : superclasses.getFlattened()) {
            if (!oce.isAnonymous() && oce.asOWLClass().getIRI().toURI().equals(parentUri)) {
            	// Found the parent URI - break the loop
                ret = true;
                break;
            }
        }
        
        return ret;
    }
    
	private List<String> findLabels(IRI iri) {
        if (!labels.containsKey(iri)) {
            Set<String> classNames = new HashSet<>();

            // get label annotation property
        	OWLAnnotationProperty labelAnnotationProperty = ontology.getOWLOntologyManager().getOWLDataFactory()
        			.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI());
        	
        	classNames = new HashSet<>(findAnnotations(iri, labelAnnotationProperty));

        	labels.put(iri, classNames);
        }
		
        return new ArrayList<>(labels.get(iri));
	}
	
	private Collection<String> findAnnotations(IRI iri, OWLAnnotationProperty typeAnnotation) {
        Set<String> classNames = new HashSet<>();

        // get all label annotations
        for (OWLAnnotation labelAnnotation : Searcher.annotations(ontology.getAnnotationAssertionAxioms(iri), typeAnnotation)) {
        	OWLAnnotationValue labelAnnotationValue = labelAnnotation.getValue();
        	if (labelAnnotationValue instanceof OWLLiteral) {
        		classNames.add(((OWLLiteral) labelAnnotationValue).getLiteral());
        	}
        }
		
        return classNames;
	}
	
	private List<String> findLabelsByAnnotationURI(OWLClass owlClass, String annotationUri) {
        // get synonym annotation property
		OWLAnnotationProperty annotationProperty = ontology.getOWLOntologyManager().getOWLDataFactory()
				.getOWLAnnotationProperty(IRI.create(annotationUri));
        
        return new ArrayList<>(findAnnotations(owlClass.getIRI(), annotationProperty));
	}
	
	private Collection<String> getSubClassUris(OWLClass owlClass, boolean direct) {
    	return getUrisFromNodeSet(reasoner.getSubClasses(owlClass, direct));
    }

    private Collection<String> getSuperClassUris(OWLClass owlClass, boolean direct) {
    	return getUrisFromNodeSet(reasoner.getSuperClasses(owlClass, direct));
    }
    
    private Collection<String> getUrisFromNodeSet(NodeSet<OWLClass> nodeSet) {
    	Set<String> uris = new HashSet<>();
    	
    	for (Node<OWLClass> node : nodeSet) {
    		for (OWLClass expr : node.getEntities()) {
    			if (!expr.isAnonymous()) {
    				IRI iri = expr.asOWLClass().getIRI();
    				if (!iri.equals(owlNothingIRI)) {
	    				uris.add(iri.toURI().toString());
    				}
    			}
    		}
    	}
    	
    	return uris;
    }

    private Map<String, List<String>> getRestrictions(OWLClass owlClass) {
    	Map<String, List<RelatedItem>> items = buildRelatedItemMap(owlClass);
    	Map<String, List<String>> restrictions = new HashMap<>();
    	
    	for (String relation : items.keySet()) {
    		// Create the relation key
    		String key = relation + RELATION_FIELD_SUFFIX;
    		List<String> uris = new ArrayList<>(items.get(relation).size());
    		
    		for (RelatedItem item : items.get(relation)) {
    			uris.add(item.getIri().toURI().toString());
    		}
    		
    		restrictions.put(key, uris);
    	}
    	
    	return restrictions;
    }
    
    public Map<String, List<RelatedItem>> buildRelatedItemMap(OWLClass owlClass) {
		for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(owlClass)) {
			OWLClassExpression superCls = ax.getSuperClass();
			// Ask our superclass to accept a visit from the RestrictionVisitor
			// - if it is an existential restriction then our restriction visitor
			// will answer it - if not our visitor will ignore it
			superCls.accept(restrictionVisitor);
		}
		
		Map<String, List<RelatedItem>> restrictions = new HashMap<>();
		for (OWLObjectSomeValuesFrom val : restrictionVisitor.getSomeValues()) {
			OWLPropertyExpression prop = val.getProperty();
			OWLClassExpression exp = val.getFiller();
			
			// Get the shortname of the property expression
			String shortForm = null;
			Set<OWLObjectProperty> signatureProps = prop.getObjectPropertiesInSignature();
			for (OWLObjectProperty sigProp : signatureProps) {
				Collection<String> labels = findLabels(sigProp.getIRI());
				if (labels.size() > 0) {
					shortForm = new ArrayList<String>(labels).get(0);
				}
			}

			if (shortForm != null && !exp.isAnonymous()) {
				// Get the labels of the class expression
				IRI iri = exp.asOWLClass().getIRI();
				Set<String> labels = new HashSet<>(findLabels(iri));
				
				if (!restrictions.containsKey(shortForm)) {
					restrictions.put(shortForm, new ArrayList<RelatedItem>());
				}
				restrictions.get(shortForm).add(new RelatedItem(iri, labels));
			}
		}
		
		return restrictions;
    }
    
}