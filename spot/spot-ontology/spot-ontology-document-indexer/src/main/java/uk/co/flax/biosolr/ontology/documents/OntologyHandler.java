/**
 * Copyright (c) 2014 Lemur Consulting Ltd.
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
package uk.co.flax.biosolr.ontology.documents;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
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
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.ebi.fgpt.owl2json.OntologyHierarchyNode;
import uk.ac.ebi.fgpt.owl2json.OntologyHierarchyNodeCounter;
import uk.ac.ebi.fgpt.owl2json.SimpleOntologyHierarchyNode;
import uk.ac.ebi.fgpt.owl2json.ZoomaNodeCounter;
import uk.co.flax.biosolr.ontology.indexer.RelatedItem;
import uk.co.flax.biosolr.ontology.indexer.visitors.RestrictionVisitor;

/**
 * @author Matt Pearce
 */
public class OntologyHandler {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(OntologyHandler.class);
	
	public static final String ORGANIZATIONAL_CLASS_URI = "http://www.ebi.ac.uk/efo/organizational_class";
	
	private final OWLOntology ontology;
    private final OWLReasoner reasoner;
    private final ShortFormProvider shortFormProvider;
    private final OntologyHierarchyNodeCounter nodeCounter;
    
	private final Map<IRI, OWLClass> owlClassMap = new HashMap<>();
	
	private Map<IRI, Collection<String>> labels = new HashMap<>();
	
	private final OWLAnnotationProperty organizationalClass;
	private final IRI owlNothingIRI;

	/**
	 * Construct a handler for a particular ontology.
	 * 
	 * @param ontologyUri the URI for the ontology.
	 * @throws OWLOntologyCreationException 
	 */
	public OntologyHandler(String ontologyUri) throws OWLOntologyCreationException {
        // Set property to make sure we can parse all of EFO
        System.setProperty("entityExpansionLimit", "1000000");
        
        LOGGER.info("Loading ontology from " + ontologyUri + "...");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI iri = IRI.create(ontologyUri);
        this.ontology = manager.loadOntologyFromOntologyDocument(iri);
		this.reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
		this.shortFormProvider = new SimpleShortFormProvider();
		this.nodeCounter = new ZoomaNodeCounter();
        
        // Initialise the class map
        initialiseClassMap();
        
        // Set up the organizational class annotation property
		this.organizationalClass = findAnnotationProperty(ORGANIZATIONAL_CLASS_URI);
        this.owlNothingIRI = manager.getOWLDataFactory().getOWLNothing().getIRI();
	}
	
	private void initialiseClassMap() {
		for (OWLClass clazz : ontology.getClassesInSignature()) {
			owlClassMap.put(clazz.getIRI(), clazz);
		}
	}
	
	private OWLAnnotationProperty findAnnotationProperty(String propertyIRI) {
		return ontology.getOWLOntologyManager().getOWLDataFactory()
				.getOWLAnnotationProperty(IRI.create(propertyIRI));
	}
	
	public OWLOntology getOntology() {
		return ontology;
	}
	
	public ShortFormProvider getShortFormProvider() {
		return shortFormProvider;
	}
	
	public OWLClass findOWLClass(String uri) {
		OWLClass ret = null;
		
		if (StringUtils.isNotBlank(uri)) {
			IRI iri = IRI.create(uri);
			ret = owlClassMap.get(iri);
		}
		
		return ret;
	}
	
	public Collection<String> findLabels(OWLClass owlClass) {
		return findLabels(owlClass.getIRI());
 	}
	
	private Collection<String> findLabels(IRI iri) {
        Set<String> classNames = new HashSet<>();

        if (!labels.containsKey(iri)) {
        	// get label annotation property
        	OWLAnnotationProperty labelAnnotationProperty = ontology.getOWLOntologyManager().getOWLDataFactory()
        			.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI());
        	
        	classNames = new HashSet<>(findAnnotations(iri, labelAnnotationProperty));

        	labels.put(iri, classNames);
        }
		
        return labels.get(iri);
	}
	
	public Collection<String> findLabelsByAnnotationURI(OWLClass owlClass, String annotationUri) {
        // get synonym annotation property
		OWLAnnotationProperty annotationProperty = ontology.getOWLOntologyManager().getOWLDataFactory()
				.getOWLAnnotationProperty(IRI.create(annotationUri));
        
        return findAnnotations(owlClass.getIRI(), annotationProperty);
	}
	
	private Collection<String> findAnnotations(IRI iri, OWLAnnotationProperty typeAnnotation) {
        Collection<String> classNames = new HashSet<String>();
    	
        // get all label annotations
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(iri)) {
        	OWLAnnotation labelAnnotation = axiom.getAnnotation();
        	OWLAnnotationValue labelAnnotationValue = labelAnnotation.getValue();
        	if (labelAnnotationValue instanceof OWLLiteral) {
        		classNames.add(((OWLLiteral) labelAnnotationValue).getLiteral());
        	}
        }

        return classNames;
	}
	
	public Collection<String> getSubClassUris(OWLClass owlClass) {
    	return getSubClassUris(owlClass, true);
    }

	public Collection<String> getSubClassUris(OWLClass owlClass, boolean direct) {
    	return getUrisFromNodeSet(reasoner.getSubClasses(owlClass, direct));
    }

    public Collection<String> getSuperClassUris(OWLClass owlClass) {
    	return getSuperClassUris(owlClass, true);
    }
    
    public Collection<String> getSuperClassUris(OWLClass owlClass, boolean direct) {
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

	public Collection<String> findChildLabels(OWLClass owlClass, boolean direct) {
		return getLabelsFromNodeSet(reasoner.getSubClasses(owlClass, direct));
	}

	public Collection<String> findParentLabels(OWLClass owlClass, boolean direct) {
		return getLabelsFromNodeSet(reasoner.getSuperClasses(owlClass, direct));
	}
	
	private Collection<String> getLabelsFromNodeSet(NodeSet<OWLClass> nodeSet) {
		Set<String> labels = new HashSet<>();
		
    	for (Node<OWLClass> node : nodeSet) {
    		for (OWLClass expr : node.getEntities()) {
    			if (!expr.isAnonymous() && !isOrganisationalClass(expr)) {
        			Collection<String> parentLabels = findLabels(expr.asOWLClass());
        			labels.addAll(parentLabels);
    			}
    		}
    	}
    	
    	return labels;
	}
	
	public List<OntologyHierarchyNode> getChildHierarchy(OWLClass parent) {
		List<OntologyHierarchyNode> hierarchy = new ArrayList<>();
		
		for (Node<OWLClass> child : reasoner.getSubClasses(parent, true)) {
			OWLClass childClass = filterOWLClassNode(child);
			if (childClass != null) {
				hierarchy.add(buildOntologyNode(childClass));
			}
		}
		
		return hierarchy;
	}
	
	private OntologyHierarchyNode buildOntologyNode(OWLClass owlClass) {
		URI uri = owlClass.getIRI().toURI();
		String label = "";
		Iterator<String> labelIter = findLabels(owlClass).iterator();
		if (labelIter.hasNext()) {
			label = labelIter.next();
		}
		List<OntologyHierarchyNode> children = new ArrayList<>();
		
		for (Node<OWLClass> child : reasoner.getSubClasses(owlClass, true)) {
			OWLClass childClass = filterOWLClassNode(child);
			if (childClass != null) {
				children.add(buildOntologyNode(childClass));
			}
		}
		
		OntologyHierarchyNode node = new SimpleOntologyHierarchyNode(uri, label, children);
		
		// Set the size last, after all children have been initialised
		node.setSize(nodeCounter.count(node));
		
		return node;
	}
	
	private OWLClass filterOWLClassNode(Node<OWLClass> classNode) {
		OWLClass owlClass = classNode.getRepresentativeElement();
		if (owlClass.getIRI().equals(owlNothingIRI)) {
			owlClass = null;
		}
		return owlClass;
	}
	
    public Map<String, List<RelatedItem>> getRestrictions(OWLClass owlClass) {
    	RestrictionVisitor visitor = new RestrictionVisitor(Collections.singleton(ontology));
		for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(owlClass)) {
			OWLClassExpression superCls = ax.getSuperClass();
			// Ask our superclass to accept a visit from the RestrictionVisitor
			// - if it is an existential restriction then our restriction visitor
			// will answer it - if not our visitor will ignore it
			superCls.accept(visitor);
		}
		
		Map<String, List<RelatedItem>> restrictions = new HashMap<>();
		for (OWLObjectSomeValuesFrom val : visitor.getSomeValues()) {
			OWLClassExpression exp = val.getFiller();
			
			// Get the shortname of the property expression
			String shortForm = null;
			Set<OWLObjectProperty> signatureProps = val.getProperty().getObjectPropertiesInSignature();
			for (OWLObjectProperty sigProp : signatureProps) {
				Collection<String> labels = findLabels(sigProp.getIRI());
				if (labels.size() > 0) {
					shortForm = new ArrayList<String>(labels).get(0);
				}
			}

			if (shortForm != null && !exp.isAnonymous()) {
				// Get the labels of the class expression
				Set<String> labels = new HashSet<>(findLabels(exp.asOWLClass()));
				IRI iri = exp.asOWLClass().getIRI();
				
				if (!restrictions.containsKey(shortForm)) {
					restrictions.put(shortForm, new ArrayList<RelatedItem>());
				}
				restrictions.get(shortForm).add(new RelatedItem(iri, labels));
			}
		}
		
		return restrictions;
    }
    
    public boolean isChildOf(OWLClass owlClass, URI parentUri) {
        NodeSet<OWLClass> superclasses = reasoner.getSuperClasses(owlClass, false);
        for (OWLClassExpression oce : superclasses.getFlattened()) {
            if (!oce.isAnonymous() && oce.asOWLClass().getIRI().toURI().equals(parentUri)) {
                return true;
            }
        }
        // if no superclasses are obsolete, this class isn't obsolete
        return false;
    }
    
    private boolean isOrganisationalClass(OWLClass owlClass) {
		return !findAnnotations(owlClass.getIRI(), organizationalClass).isEmpty();
    }
    
}
