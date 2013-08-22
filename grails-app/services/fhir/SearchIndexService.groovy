package fhir;

import fhir.searchParam.SearchParamHandler
import fhir.searchParam.SearchParamValue
import groovy.util.logging.Log4j

import javax.annotation.PostConstruct

import org.codehaus.groovy.grails.commons.GrailsApplication
import org.hl7.fhir.instance.formats.XmlComposer
import org.hl7.fhir.instance.formats.XmlParser
import org.hl7.fhir.instance.model.Conformance
import org.hl7.fhir.instance.model.Resource
import org.hl7.fhir.instance.model.Conformance.ConformanceRestResourceComponent
import org.hl7.fhir.instance.model.Conformance.ConformanceRestResourceSearchParamComponent
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import com.google.common.collect.ImmutableMap
import com.mongodb.BasicDBObject

class SearchIndexService{

	static def transactional = false
	static def lazyInit = false
	
	static XmlParser parser = new XmlParser()
	static XmlComposer composer = new XmlComposer()
	static GrailsApplication grailsApplication

	static Map<Class<Resource>,Collection> indexersByResource = [:]
	static Map<String, String> xpathsMissingFromFhir;
	static Map<String, String> capitalizedModelName = [:]

	@PostConstruct
	void init() {
		
		SearchParamHandler.injectGrailsApplication(grailsApplication);

		def xpathFixes = ImmutableMap.<String, String> builder();
		grailsApplication.config.fhir.searchParam.spotFixes.each {  
			uri, xpath -> xpathFixes.put(uri, xpath)
		}
		xpathsMissingFromFhir = xpathFixes.build()
		
		def conformance = resourceFromFile "profile.xml"
		setConformance(conformance)
	}

	public Class<Resource> classForModel(String modelName){
		modelName = capitalizedModelName[modelName]?:modelName
		if(modelName.equals("String")){
			modelName += "_";
		}
		if(modelName.equals("List")){
			modelName += "_";
		}
		log.debug("L:ooking up clasS: " + modelName)
		return lookupClass("org.hl7.fhir.instance.model."+modelName);
	}
	
	public static Resource resourceFromFile(String file) {
		def stream = classLoader.getResourceAsStream(file)
		parser.parse(stream)
	}
	
	public static Class lookupClass(String name){
		Class.forName(name,	true, classLoader)
	}
	
	public static ClassLoader getClassLoader(){
		Thread.currentThread().contextClassLoader
	}

	 
	public void setConformance(Conformance c) throws Exception {
		log.debug("Resetting CONFORMANCE!")
		def restResources = c.rest[0].resource
		restResources.each { resource ->
			capitalizedModelName[resource.typeSimple.toLowerCase()] = resource.typeSimple
			Class model = classForModel resource.typeSimple
			
			indexersByResource[model] = resource.searchParam.collect {	searchParam ->

				String key = searchParam.sourceSimple

				// Short-circuit FHIR's built-in xpath if defined. Handles:
				//  * missing xpaths -- like in Patient
				//  * broken xpaths  -- like 'f:value[x]'
				SearchParamHandler.create(
						searchParam.nameSimple,
						searchParam.typeSimple,
						xpathsMissingFromFhir[key] ?:searchParam.xpathSimple);
			}
		}
	}
	
	public List<SearchParamValue> indexResource(Resource rx) {

		log.info("\n\nINDEXING" + rx)

		def ret = indexersByResource[rx.class].collectMany {
			SearchParamHandler h -> h.execute(rx)
		}

		log.info "\n" + ret.collect {
			"indexed: "+it.paramName+"="+it.paramValue
		}.join("\n")

		return ret;
	}
	
	protected List<Map> orClausesFor(Map param){
		List<String> alternatives = param.value.split(',')
		
		return alternatives.collect {
			[
				key: param.key,
				modifier: param.modifier,
				value: it	
			]
		}
	}
	
	public BasicDBObject queryParamsToMongo(Map params){
		
		def rc = classForModel(params.resource)
		def indexers = indexersByResource[rc]
		
		def byParam = indexers.collectEntries {
			[(it.fieldName): it]
		}
				
		def searchParams = params.collect {
			k,v ->
			def c = k.split(":") as List
			[
			  key: c[0],
			  modifier: c[1],
			  value: v
			]
		  }.findAll {
			   it.key in byParam
		  }
		  
		List<BasicDBObject> clauses = searchParams.collect { param ->
			List disjunction = param.value.split(',')

			List orClauses = orClausesFor(param).collect {
				byParam[it.key].searchClause(it)
			}
			
			if (orClauses.size() == 1) return orClauses[0];
			else return SearchParamHandler.orList(orClauses);
		}
		log.debug("Clauess: " + clauses.join("\n"))
		return SearchParamHandler.andList(clauses)
	}
}