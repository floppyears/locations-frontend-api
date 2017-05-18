package edu.oregonstate.mist.locations.frontend.db

import com.fasterxml.jackson.databind.ObjectMapper
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Handles HTTP requests against ElasticSearch. Operation supported are:
 * search and findById.
 */
class LocationDAO {
    private final Map<String, String> locationConfiguration

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationDAO.class)

    LocationDAO(Map<String, String> locationConfiguration) {
        this.locationConfiguration = locationConfiguration
    }
    /**
     * Testing the elasticsearch java API
     * @param id
     * @return
     */
    String esTest (String id) {
        TransportClient client = TransportClient.builder().build()
                .addTransportAddress(new InetSocketTransportAddress(
                InetAddress.getByName("localhost"), 9300))

        GetResponse response = client.prepareGet(
                locationConfiguration.get("esIndex"),
                locationConfiguration.get("estype"),
                id
        ).execute().actionGet()

        client.close()

        response.sourceAsString
    }

    /**
     * Searches ES (elasticsearch) for locations matching "q" full text search within the given
     * campus and type.
     *
     * @param q                 Query text to use for full text search
     * @param campus            Campus to use to filter results
     * @param type              Type of location to filter results
     * @param pageNumber
     * @param pageSize
     *
     * @return json             JSON search results from ES
     */
    String search(String q, String campus, String type, Double lat,
                  Double lon, String searchDistance, Boolean isOpen,
                  Integer pageNumber, Integer pageSize) {
        ObjectMapper mapper = new ObjectMapper()

        // generate ES query to search for locations
        def esQuery = getESSearchQuery(q, campus, type,
                                       lat, lon, searchDistance,
                                       isOpen, pageNumber, pageSize)

        LOGGER.debug("elastic search query: " + esQuery)

        String esQueryJson = mapper.writeValueAsString(esQuery)

        // get data from ES
        def url = new URL("${locationsESFullUrl}/_search")
        URLConnection connection = postRequest(url, esQueryJson)
        connection.content.text
    }

    /**
     * Performs a search / list against the services index.
     *
     * @param q
     * @param isOpen
     * @param pageNumber
     * @param pageSize
     * @return
     */
    String searchService(String q, Boolean isOpen, Integer pageNumber,
                         Integer pageSize) {
        ObjectMapper mapper = new ObjectMapper()

        // generate ES query to search for locations
        def esQuery = getESSearchQuery(q, null, null,
                                       null, null, null,
                                       isOpen, pageNumber, pageSize)

        LOGGER.debug("elastic search query: " + esQuery)

        String esQueryJson = mapper.writeValueAsString(esQuery)

        // get data from ES
        def url = new URL("${servicesESFullUrl}/_search")
        URLConnection connection = postRequest(url, esQueryJson)
        connection.content.text
    }

    /**
     * Returns the related services mapped to a building / location
     *
     * @param locationId
     * @param pageNumber
     * @param pageSize
     * @return
     */
    String getRelatedServices(String locationId, Integer pageNumber, Integer pageSize) {
        ObjectMapper mapper = new ObjectMapper()

        // generate ES query to search for locations
        def esQuery = getESQueryRelatedServices(locationId, pageNumber, pageSize)

        LOGGER.debug("elastic search query: " + esQuery)

        String esQueryJson = mapper.writeValueAsString(esQuery)

        // get data from ES
        def url = new URL("${servicesESFullUrl}/_search")
        URLConnection connection = postRequest(url, esQueryJson)
        connection.content.text
    }

    /**
     * Return a single location object with the matching id
     *
     * @param id
     * @return
     */
    String getById(String id) {
        try {
            return "${locationsESFullUrl}/${id?.toLowerCase()}/_source".toURL().text
        } catch (FileNotFoundException e) {
            return null
        }
    }

    /**
     * Returns a single service
     *
     * @param id
     * @return
     */
    String getServiceById(String id) {
        try {
            return "${servicesESFullUrl}/${id?.toLowerCase()}/_source".toURL().text
        } catch (FileNotFoundException e) {
            return null
        }
    }

    /**
     * Returns url of elastic search collection and type to search.
     *
     * @return
     */
    private GString getESFullUrl(String esIndex, String esType) {
        String esUrl = locationConfiguration.get("esUrl")

        "${esUrl}/${esIndex}/${esType}"
    }

    private GString getLocationsESFullUrl() {
        getESFullUrl(locationConfiguration.get("esIndex"), locationConfiguration.get("estype"))
    }

    private GString getServicesESFullUrl() {
        getESFullUrl(locationConfiguration.get("esIndexService"),
                locationConfiguration.get("estypeService"))
    }

    /**
     * Performs POST request.
     *
     * This groovy approach is used instead of jerseyClient because jersey client kept
     * throwing no response errors.
     *
     * @param url
     * @param esQueryJson
     * @return
     */
    private URLConnection postRequest(URL url, String esQueryJson) {
        def connection = url.openConnection()
        connection.setRequestMethod("POST")
        connection.doOutput = true

        def writer = new OutputStreamWriter(connection.outputStream)
        writer.write(esQueryJson)
        writer.flush()
        writer.close()
        connection.connect()
        connection
    }

    /**
     * Generate ElasticSearch query to list locations by campus, type and full text search.
     *
     * @param campus
     * @param type
     * @param q
     * @param pageNumber
     * @param pageSize
     * @param query
     * @return
     */
    private def getESSearchQuery(String q, String campus, String type,
                                 Double lat, Double lon, String searchDistance,
                                 Boolean isOpen,int pageNumber, int pageSize) {
        def esQuery = [
            "query": [
                "bool": [
                    "must": [],
                    "filter": []
                ]
            ],
            "sort": [],
            "from": (pageNumber - 1) * pageSize,
            "size": pageSize
        ]

        if (campus) {
            esQuery.query.bool.must += [ "match": [ "attributes.campus": campus ]]
        }

        if (type) {
            if (type == "cultural-center") {
                esQuery.query.bool.must += [ "match": [ "attributes.tags": type ]]
            } else {
                esQuery.query.bool.must += ["match": ["attributes.type": type]]
            }
        }

        if (q) {
            esQuery.query.bool.filter += [ "multi_match" : [
                    "query":    q,
                    "fields": [ "attributes.name", "attributes.abbreviation" ]
            ]]
        }

        if (lat && lon) {
            addLocationQuery(esQuery, lat, lon, searchDistance)
        }

        if (isOpen) {
            addTimeQuery(esQuery)
        }

        esQuery
    }

    private def getESQueryRelatedServices(String locationId, int pageNumber, int pageSize) {
        [
            "query": [
                "bool": [
                    "must": [ "match": [ "attributes.locationId": locationId ]]
                ]
            ],
            "sort": [],
            "from": (pageNumber - 1) * pageSize,
            "size": pageSize
        ]
    }

    /**
     * Add ES query for searching by location
     * @param esQuery
     * @param lat
     * @param lon
     */
    private static void addLocationQuery(def esQuery, Double lat,
                                         Double lon, String searchDistance) {
        esQuery.query.bool.filter += ["geo_distance": [
                "distance": searchDistance,
                "attributes.geoLocation": [
                        "lat": lat,
                        "lon": lon
                ]
        ]]

        esQuery.sort = [
                        "_geo_distance": [
                                "attributes.geoLocation": [
                                        "lat":  lat,
                                        "lon": lon
                                ],
                                "order":         "asc",
                                "unit":          "km",
                                "distance_type": "plane"]
                ]
    }

    /**
     * Add ES query for filtering currently open restaurants
     * @param esQuery
     */
    private static void addTimeQuery(def esQuery) {
        String weekday = Integer.toString(DateTime.now().getDayOfWeek())
        esQuery.query.bool.filter += [
                ["nested": [
                        "path": "attributes.openHours." + weekday,
                        "filter": [
                                [ "range":["attributes.openHours.${weekday}.start":
                                                   [ "lte": "now"]]],
                                ["range": ["attributes.openHours.${weekday}.end":
                                                   ["gt": "now"]]]]]]]
    }
}
