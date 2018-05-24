package com.specmate.search.internal.services;

import static com.specmate.search.config.LuceneBasedSearchServiceConfig.KEY_LUCENE_DB_LOCATION;
import static com.specmate.search.config.LuceneBasedSearchServiceConfig.KEY_MAX_SEARCH_RESULTS;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.QueryParser.Operator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.log.LogService;

import com.specmate.common.SpecmateException;
import com.specmate.common.SpecmateInvalidQueryException;
import com.specmate.common.SpecmateValidationException;
import com.specmate.persistency.IPersistencyService;
import com.specmate.persistency.IView;
import com.specmate.persistency.event.ModelEvent;
import com.specmate.search.api.IModelSearchService;
import com.specmate.search.config.LuceneBasedSearchServiceConfig;

/**
 * Service that provides a search facility via Apache Lucene. It registers with
 * the OSGI event admin not listen for model changes.
 * 
 * @author junkerm
 *
 */
@Component(configurationPid = LuceneBasedSearchServiceConfig.PID, configurationPolicy = ConfigurationPolicy.REQUIRE, service = {
		IModelSearchService.class, EventHandler.class }, property = { "event.topics=com/specmate/model/notification",
				"event.topics=com/specmate/model/notification/*" })
public class LuceneBasedModelSearchService implements EventHandler, IModelSearchService {

	/** The name of the UserSession class */
	private static final String USER_SESSION = "UserSession";

	/** Time to wait in seconds before committing the changes to the index */
	private static final int COMMIT_RATE = 30;

	/** Pattern to extract to project name from an event topic */
	Pattern pattern = Pattern.compile("com\\/specmate\\/model\\/notification\\/([^\\/]+)");

	/** The persistency service to access the model data */
	private IPersistencyService persistencyService;

	/** The view through which the model data is accessed. */
	private IView view;

	/** The lucene directory. */
	private Directory directory;

	/** Index writer to modify the lucene database */
	private IndexWriter indexWriter;

	/** Search manager to perform searches on the lucene database. */
	private SearcherManager searcherManager;

	/** Service for message logging. */
	private LogService logService;

	/** Periodic scheduler for the periodic commit */
	private ScheduledExecutorService scheduledExecutor;

	/** Location of the lucene database */
	private String luceneDbLocation;

	/** Maximum number of search results to return */
	private int maxSearchResults;

	/** The analyzer that is used. */
	private StandardAnalyzer analyzer;

	/**
	 * Service activation
	 * 
	 * @throws SpecmateValidationException
	 */
	@Activate
	public void activate(Map<String, Object> properties) throws SpecmateException, SpecmateValidationException {
		readConfig(properties);
		this.view = persistencyService.openView();

		try {
			initializeLucene();
			startPeriodicCommitThread();
		} catch (IOException e) {
			logService.log(LogService.LOG_ERROR, "Could not open index for full-text search.");
		}
	}

	/** Service Deactivation */
	@Deactivate
	public void deactivate() {
		if (this.indexWriter != null) {
			try {
				this.indexWriter.close();
			} catch (IOException e) {
				logService.log(LogService.LOG_ERROR, "Could not close full-text index.");
			}
		}
		if (this.scheduledExecutor != null) {
			this.scheduledExecutor.shutdown();
		}
	}

	private void readConfig(Map<String, Object> properties) throws SpecmateValidationException {
		String errMsg = "Missing config for %s";
		if (!properties.containsKey(KEY_LUCENE_DB_LOCATION)) {
			throw new SpecmateValidationException(String.format(errMsg, KEY_LUCENE_DB_LOCATION));
		} else {
			this.luceneDbLocation = (String) properties.get(KEY_LUCENE_DB_LOCATION);
		}
		if (!properties.containsKey(KEY_MAX_SEARCH_RESULTS)) {
			throw new SpecmateValidationException(String.format(errMsg, KEY_MAX_SEARCH_RESULTS));
		} else {
			this.maxSearchResults = (int) properties.get(KEY_MAX_SEARCH_RESULTS);
		}
		// if (!properties.containsKey(KEY_ALLOWED_FIELDS)) {
		// throw new SpecmateValidationException(String.format(errMsg,
		// KEY_MAX_SEARCH_RESULTS));
		// } else {
		// this.allowedFields = (String[]) properties.get(KEY_ALLOWED_FIELDS);
		// }
	}

	/**
	 * Starts a thread that performs a commit to the lucene database
	 * periodicylly.
	 */
	private void startPeriodicCommitThread() {
		this.scheduledExecutor = Executors.newScheduledThreadPool(3);
		scheduledExecutor.scheduleWithFixedDelay(() -> {
			try {
				indexWriter.commit();
				searcherManager.maybeRefresh();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}, COMMIT_RATE, COMMIT_RATE, TimeUnit.SECONDS);
	}

	/** Initialize the access to the lucene database */
	private void initializeLucene() throws IOException {
		this.analyzer = new StandardAnalyzer();
		directory = FSDirectory.open(Paths.get(luceneDbLocation));
		IndexWriterConfig config = new IndexWriterConfig(analyzer);
		indexWriter = new IndexWriter(directory, config);
		this.searcherManager = new SearcherManager(indexWriter, true, true, null);
	}

	/** Performs a search with the given field/value-list query. */
	@Override
	public Set<EObject> search(String queryString, String project)
			throws SpecmateException, SpecmateInvalidQueryException {
		// QueryParser not thread-safe, hence create new for each search
		String projectPrefix = "(" + FieldConstants.FIELD_PROJECT + ":" + project + ") ";
		QueryParser queryParser = new MultiFieldQueryParser(FieldConstants.SEARCH_FIELDS, analyzer);
		queryParser.setDefaultOperator(Operator.AND);
		Query query;
		try {
			query = queryParser.parse(projectPrefix + queryString);
		} catch (ParseException e) {
			logService.log(LogService.LOG_ERROR, "Counld not parse query: " + queryString, e);
			throw new SpecmateInvalidQueryException("Could not parse query: " + queryString, e);
		}

		IndexSearcher isearcher;
		try {
			isearcher = searcherManager.acquire();
		} catch (IOException e) {
			throw new SpecmateException("Could not aquire index searcher.", e);
		}

		try {
			return performSearch(query, isearcher);
		} catch (IOException e) {
			throw new SpecmateException("IO error while searching lucene database.", e);
		} finally {
			try {
				searcherManager.release(isearcher);
			} catch (IOException e) {
				logService.log(LogService.LOG_ERROR, "Error while releasing lucene searcher.", e);
			}
		}
	}

	@Override
	public void clear() throws SpecmateException {

		try {
			indexWriter.deleteAll();
			indexWriter.commit();
			searcherManager.maybeRefresh();
		} catch (IOException e) {
			throw new SpecmateException(e);
		}
	}

	/** Performs the given lucene query on the given searcher. */
	private Set<EObject> performSearch(Query query, IndexSearcher isearcher) throws IOException {
		ScoreDoc[] hits;
		hits = isearcher.search(query, this.maxSearchResults).scoreDocs;
		Set<EObject> result = new HashSet<>();
		// Iterate through the results:
		for (int i = 0; i < hits.length; i++) {
			Document hitDoc = isearcher.doc(hits[i].doc);
			String id = hitDoc.get(FieldConstants.FIELD_ID);
			EObject object = view.getObjectById(id);
			if (object != null) {
				result.add(object);
			}
		}
		return result;
	}

	/**
	 * Handles a model event. Updates the lucene database in case the model has
	 * changed.
	 */
	@Override
	public void handleEvent(Event event) {
		if (!(event instanceof ModelEvent)) {
			return;
		}
		ModelEvent modelEvent = (ModelEvent) event;
		String className = modelEvent.getClassName();

		// Exclude UserSession objects from being indexed
		if (className != null && className.equals(USER_SESSION)) {
			return;
		}

		String project = extractProject(modelEvent.getTopic());
		switch (modelEvent.getType()) {
		case NEW:
			Document document = getDocumentForModelObject(modelEvent.getId(), project, className,
					modelEvent.getFeatureMap());
			if (document == null) {
				break;
			}
			try {
				indexWriter.addDocument(document);
			} catch (IOException e) {
				this.logService.log(LogService.LOG_ERROR, "Could not add document to index: " + event.getTopic(), e);
			}
			break;
		case DELETE:
			try {
				indexWriter.deleteDocuments(new Term(FieldConstants.FIELD_ID, modelEvent.getId()));
			} catch (IOException e) {
				this.logService.log(LogService.LOG_ERROR, "Could not delete document from index: " + event.getTopic(),
						e);
			}
			break;
		default:
			updateIndex(modelEvent.getId(), project);
		}
	}

	/** Extract the project name from an event topic */
	private String extractProject(String topic) {

		Matcher matcher = pattern.matcher(topic);
		if (matcher.find()) {
			String result = matcher.group(1);
			return result;
		}
		return "";
	}

	/**
	 * Updates the index for the item with the given id with the given
	 * feature/value mapping
	 */
	private void updateIndex(String id, String project) {
		EObject object = view.getObjectById(id);
		Map<EStructuralFeature, Object> featureMap = new HashMap<>();
		for (EAttribute attribute : object.eClass().getEAllAttributes()) {
			featureMap.put(attribute, object.eGet(attribute));
		}
		Document doc = getDocumentForModelObject(id, project, object.eClass().getName(), featureMap);
		try {
			indexWriter.updateDocument(new Term(FieldConstants.FIELD_ID, id), doc);
		} catch (IOException e) {
			this.logService.log(LogService.LOG_ERROR, "Could not update index: " + id, e);
		}
	}

	/** Produces a document for a model given as a fature/value mapping. */
	private Document getDocumentForModelObject(String id, String project, String className,
			Map<EStructuralFeature, Object> featureMap) {
		return DocumentFactory.create(className, id, project, featureMap);
	}

	/** Sets the persistency service */
	@Reference
	public void setPersistency(IPersistencyService persistencyService) {
		this.persistencyService = persistencyService;
	}

	/** Sets the log service. */
	@Reference
	public void setLogService(LogService logService) {
		this.logService = logService;
	}
}
