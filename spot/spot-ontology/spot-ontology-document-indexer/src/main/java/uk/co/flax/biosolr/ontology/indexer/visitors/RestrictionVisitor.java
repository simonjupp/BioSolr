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
package uk.co.flax.biosolr.ontology.indexer.visitors;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.util.OWLClassExpressionVisitorAdapter;

/**
 * @author Matt Pearce
 */
/**
 * Visits existential restrictions and collects the properties which are
 * restricted.
 */
public class RestrictionVisitor extends OWLClassExpressionVisitorAdapter {
	
	@Nonnull
	private final Set<OWLClass> processedClasses;
	private final Set<OWLOntology> onts;
	
	private final Set<OWLObjectSomeValuesFrom> someValues;
	private final Set<OWLObjectAllValuesFrom> allValues;
	
	private final Set<OWLObjectPropertyExpression> restrictedProperties;
	
	public RestrictionVisitor(Set<OWLOntology> onts) {
		processedClasses = new HashSet<>();
		restrictedProperties = new HashSet<>();
		someValues = new HashSet<>();
		allValues = new HashSet<>();
		this.onts = onts;
	}

	@Override
	public void visit(OWLClass ce) {
		if (!processedClasses.contains(ce)) {
			// If we are processing inherited restrictions then we
			// recursively visit named supers. Note that we need to keep
			// track of the classes that we have processed so that we don't
			// get caught out by cycles in the taxonomy
			processedClasses.add(ce);
			for (OWLOntology ont : onts) {
				for (OWLSubClassOfAxiom ax : ont.getSubClassAxiomsForSubClass(ce)) {
					ax.getSuperClass().accept(this);
				}
			}
		}
	}
	
	public void visit(@Nonnull OWLObjectAllValuesFrom values) {
		allValues.add(values);
	}

	@Override
	public void visit(@Nonnull OWLObjectSomeValuesFrom ce) {
		// This method gets called when a class expression is an existential
		// (someValuesFrom) restriction and it asks us to visit it
		someValues.add(ce);
		restrictedProperties.add(ce.getProperty());
	}
	
	public Set<OWLObjectPropertyExpression> getRestrictedProperties() {
		return restrictedProperties;
	}

	/**
	 * @return the someValues
	 */
	public Set<OWLObjectSomeValuesFrom> getSomeValues() {
		return someValues;
	}

	/**
	 * @return the allValues
	 */
	public Set<OWLObjectAllValuesFrom> getAllValues() {
		return allValues;
	}

}
