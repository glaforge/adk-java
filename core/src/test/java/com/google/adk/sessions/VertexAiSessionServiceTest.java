package com.google.adk.sessions;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link VertexAiSessionService}. */
@RunWith(JUnit4.class)
public class VertexAiSessionServiceTest {

  private static final ObjectMapper mapper = JsonBaseModel.getMapper();

  private static final String MOCK_SESSION_STRING_1 =
      """
      {
        "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/1",
        "createTime" : "2024-12-12T12:12:12.123456Z",
        "userId" : "user",
        "updateTime" : "2024-12-12T12:12:12.123456Z",
        "sessionState" : {
          "key" : {
            "value" : "testValue"
          }
        }
      }\
      """;

  private static final String MOCK_SESSION_STRING_2 =
      """
      {
        "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/2",
        "userId" : "user",
        "updateTime" : "2024-12-13T12:12:12.123456Z"
      }\
      """;

  private static final String MOCK_SESSION_STRING_3 =
      """
      {
        "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/3",
        "updateTime" : "2024-12-14T12:12:12.123456Z",
        "userId" : "user2"
      }\
      """;

  private static final String MOCK_EVENT_STRING =
      """
      [
        {
          "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/1/events/123",
          "invocationId" : "123",
          "author" : "user",
          "timestamp" : "2024-12-12T12:12:12.123456Z",
          "content" : {
            "role" : "user",
            "parts" : [
              { "text" : "testContent" }
            ]
          },
          "actions" : {
            "stateDelta" : {
              "key" : {
                "value" : "testValue"
              }
            },
            "transferAgent" : "agent"
          },
          "eventMetadata" : {
            "partial" : false,
            "turnComplete" : true,
            "interrupted" : false,
            "branch" : "",
            "longRunningToolIds" : [ "tool1" ]
          }
        }
      ]
      """;

  @SuppressWarnings("unchecked")
  private static Session getMockSession() throws Exception {
    Map<String, Object> sessionJson =
        mapper.readValue(MOCK_SESSION_STRING_1, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> eventJson =
        mapper
            .readValue(MOCK_EVENT_STRING, new TypeReference<List<Map<String, Object>>>() {})
            .get(0);
    Map<String, Object> sessionState = (Map<String, Object>) sessionJson.get("sessionState");
    return Session.builder("1")
        .appName("123")
        .userId("user")
        .state(sessionState == null ? null : new ConcurrentHashMap<>(sessionState))
        .lastUpdateTime(Instant.parse((String) sessionJson.get("updateTime")))
        .events(
            Arrays.asList(
                Event.builder()
                    .id("123")
                    .invocationId("123")
                    .author("user")
                    .timestamp(Instant.parse((String) eventJson.get("timestamp")).toEpochMilli())
                    .content(Content.fromParts(Part.fromText("testContent")))
                    .actions(
                        EventActions.builder()
                            .transferToAgent("agent")
                            .stateDelta(
                                sessionState == null ? null : new ConcurrentHashMap<>(sessionState))
                            .build())
                    .partial(false)
                    .turnComplete(true)
                    .interrupted(false)
                    .branch("")
                    .longRunningToolIds(ImmutableSet.of("tool1"))
                    .build()))
        .build();
  }

  /** Mock for HttpApiClient to mock the http calls to Vertex AI API. */
  @Mock private HttpApiClient mockApiClient;

  @Mock private ApiResponse mockApiResponse;
  private VertexAiSessionService vertexAiSessionService;
  private Map<String, String> sessionMap = null;
  private Map<String, String> eventMap = null;

  private static final Pattern LRO_REGEX = Pattern.compile("^operations/([^/]+)$");
  private static final Pattern SESSION_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+)$");
  private static final Pattern SESSIONS_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions$");
  private static final Pattern SESSIONS_FILTER_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions\\?filter=user_id=([^/]+)$");
  private static final Pattern APPEND_EVENT_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+):appendEvent$");
  private static final Pattern EVENTS_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+)/events$");

  private static class MockApiAnswer implements Answer<ApiResponse> {
    private final Map<String, String> sessionMap;
    private final Map<String, String> eventMap;
    private final ApiResponse mockApiResponse;

    private MockApiAnswer(
        Map<String, String> sessionMap, Map<String, String> eventMap, ApiResponse mockApiResponse) {
      this.sessionMap = sessionMap;
      this.eventMap = eventMap;
      this.mockApiResponse = mockApiResponse;
    }

    @Override
    public ApiResponse answer(InvocationOnMock invocation) throws Throwable {
      String httpMethod = invocation.getArgument(0);
      String path = invocation.getArgument(1);
      if (httpMethod.equals("POST") && SESSIONS_REGEX.matcher(path).matches()) {
        return handleCreateSession(path, invocation);
      } else if (httpMethod.equals("GET") && SESSION_REGEX.matcher(path).matches()) {
        return handleGetSession(path);
      } else if (httpMethod.equals("GET") && SESSIONS_FILTER_REGEX.matcher(path).matches()) {
        return handleGetSessions(path);
      } else if (httpMethod.equals("POST") && APPEND_EVENT_REGEX.matcher(path).matches()) {
        return handleAppendEvent(path, invocation);
      } else if (httpMethod.equals("GET") && EVENTS_REGEX.matcher(path).matches()) {
        return handleGetEvents(path);
      } else if (httpMethod.equals("GET") && LRO_REGEX.matcher(path).matches()) {
        return handleGetLro(path);
      } else if (httpMethod.equals("DELETE")) {
        return handleDeleteSession(path);
      }
      throw new RuntimeException(
          String.format("Unsupported HTTP method: %s, path: %s", httpMethod, path));
    }

    private ApiResponse mockApiResponseWithBody(String body) {
      when(mockApiResponse.getResponseBody())
          .thenReturn(
              ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), body));
      return mockApiResponse;
    }

    private ApiResponse handleCreateSession(String path, InvocationOnMock invocation)
        throws Exception {
      String newSessionId = "4";
      Map<String, Object> requestDict =
          mapper.readValue(
              (String) invocation.getArgument(2), new TypeReference<Map<String, Object>>() {});
      Map<String, Object> newSessionData = new HashMap<>();
      newSessionData.put("name", path + "/" + newSessionId);
      newSessionData.put("userId", requestDict.get("userId"));
      newSessionData.put("sessionState", requestDict.get("sessionState"));
      newSessionData.put("updateTime", "2024-12-12T12:12:12.123456Z");

      sessionMap.put(newSessionId, mapper.writeValueAsString(newSessionData));

      return mockApiResponseWithBody(
          String.format(
              """
              {
                "name": "%s/%s/operations/111",
                "done": false
              }
              """,
              path, newSessionId));
    }

    private ApiResponse handleGetSession(String path) throws Exception {
      String sessionId = path.substring(path.lastIndexOf('/') + 1);
      if (sessionId.contains("/")) { // Ensure it's a direct session ID
        return null;
      }
      String sessionData = sessionMap.get(sessionId);
      if (sessionData != null) {
        return mockApiResponseWithBody(sessionData);
      } else {
        throw new RuntimeException("Session not found: " + sessionId);
      }
    }

    private ApiResponse handleGetSessions(String path) throws Exception {
      Matcher sessionsMatcher = SESSIONS_FILTER_REGEX.matcher(path);
      if (!sessionsMatcher.matches()) {
        return null;
      }
      String userId = sessionsMatcher.group(2);
      List<String> userSessionsJson = new ArrayList<>();
      for (String sessionJson : sessionMap.values()) {
        Map<String, Object> session =
            mapper.readValue(sessionJson, new TypeReference<Map<String, Object>>() {});
        if (session.containsKey("userId") && session.get("userId").equals(userId)) {
          userSessionsJson.add(sessionJson);
        }
      }
      return mockApiResponseWithBody(
          String.format(
              """
              {
                "sessions": [%s]
              }
              """,
              String.join(",", userSessionsJson)));
    }

    private ApiResponse handleAppendEvent(String path, InvocationOnMock invocation) {
      Matcher appendEventMatcher = APPEND_EVENT_REGEX.matcher(path);
      if (!appendEventMatcher.matches()) {
        return null;
      }
      String sessionId = appendEventMatcher.group(2);
      String eventDataString = eventMap.get(sessionId);
      String newEventDataString = (String) invocation.getArgument(2);
      try {
        ConcurrentMap<String, Object> newEventData =
            mapper.readValue(
                newEventDataString, new TypeReference<ConcurrentMap<String, Object>>() {});

        List<ConcurrentMap<String, Object>> eventsData = new ArrayList<>();
        if (eventDataString != null) {
          eventsData.addAll(
              mapper.readValue(
                  eventDataString, new TypeReference<List<ConcurrentMap<String, Object>>>() {}));
        }

        newEventData.put(
            "name", path.replaceFirst(":appendEvent$", "/events/" + Event.generateEventId()));

        eventsData.add(newEventData);

        eventMap.put(sessionId, mapper.writeValueAsString(eventsData));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return mockApiResponseWithBody(newEventDataString);
    }

    private ApiResponse handleGetEvents(String path) throws Exception {
      Matcher matcher = EVENTS_REGEX.matcher(path);
      if (!matcher.matches()) {
        return null;
      }
      String sessionId = matcher.group(2);
      String eventData = eventMap.get(sessionId);
      if (eventData != null) {
        return mockApiResponseWithBody(
            String.format(
                """
                {
                  "sessionEvents": %s
                }
                """,
                eventData));
      } else {
        // Return an empty list if no events are found for the session
        return mockApiResponseWithBody("{}");
      }
    }

    private ApiResponse handleGetLro(String path) {
      return mockApiResponseWithBody(
          String.format(
              """
              {
                "name": "%s",
                "done": true
              }
              """,
              path.replace("/operations/111", ""))); // Simulate LRO done
    }

    private ApiResponse handleDeleteSession(String path) {
      Matcher sessionMatcher = SESSION_REGEX.matcher(path);
      if (!sessionMatcher.matches()) {
        return null;
      }
      String sessionIdToDelete = sessionMatcher.group(2);
      sessionMap.remove(sessionIdToDelete);
      return mockApiResponseWithBody("");
    }
  }

  @Before
  public void setUp() throws Exception {
    sessionMap =
        new HashMap<>(
            ImmutableMap.of(
                "1", MOCK_SESSION_STRING_1,
                "2", MOCK_SESSION_STRING_2,
                "3", MOCK_SESSION_STRING_3));
    eventMap = new HashMap<>(ImmutableMap.of("1", MOCK_EVENT_STRING));

    MockitoAnnotations.openMocks(this);
    vertexAiSessionService =
        new VertexAiSessionService("test-project", "test-location", mockApiClient);
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenAnswer(new MockApiAnswer(sessionMap, eventMap, mockApiResponse));
  }

  @Test
  public void createSession_success() throws Exception {
    ConcurrentMap<String, Object> sessionStateMap =
        new ConcurrentHashMap<>(ImmutableMap.of("new_key", "new_value"));
    Single<Session> sessionSingle =
        vertexAiSessionService.createSession("123", "test_user", sessionStateMap, null);
    Session createdSession = sessionSingle.blockingGet();

    // Assert that the session was created and its properties are correct
    assertThat(createdSession.userId()).isEqualTo("test_user");
    assertThat(createdSession.appName()).isEqualTo("123");
    assertThat(createdSession.state()).isEqualTo(sessionStateMap); // Check the generated IDss
    assertThat(createdSession.id()).isEqualTo("4"); // Check the generated ID

    // Verify that the session is now in the sessionMap
    assertThat(sessionMap).containsKey("4");
    String newSessionJson = sessionMap.get("4");
    Map<String, Object> newSessionMap =
        mapper.readValue(newSessionJson, new TypeReference<Map<String, Object>>() {});
    assertThat(newSessionMap.get("userId")).isEqualTo("test_user");
    assertThat(newSessionMap.get("sessionState")).isEqualTo(sessionStateMap);
  }

  @Test
  public void createSession_getSession_success() throws Exception {
    ConcurrentMap<String, Object> sessionStateMap =
        new ConcurrentHashMap<>(ImmutableMap.of("new_key", "new_value"));
    Single<Session> sessionSingle =
        vertexAiSessionService.createSession("789", "test_user", sessionStateMap, null);
    Session createdSession = sessionSingle.blockingGet();
    Session session =
        vertexAiSessionService
            .getSession("456", "test_user", createdSession.id(), Optional.empty())
            .blockingGet();

    // Verify that the session is now in the sessionMap
    assertThat(sessionMap).containsKey("4");
    assertThat(session.userId()).isEqualTo("test_user");
    assertThat(session.events()).isEmpty();
  }

  @Test
  public void createSession_noState_success() throws Exception {
    Single<Session> sessionSingle = vertexAiSessionService.createSession("123", "test_user");
    Session createdSession = sessionSingle.blockingGet();

    // Assert that the session was created and its properties are correct
    assertThat(createdSession.state()).isEmpty();

    // Verify that the session is now in the sessionMap
    assertThat(sessionMap).containsKey("4");
    String newSessionJson = sessionMap.get("4");
    Map<String, Object> newSessionMap =
        mapper.readValue(newSessionJson, new TypeReference<Map<String, Object>>() {});
    assertThat(newSessionMap.get("sessionState")).isNull();
  }

  @Test
  public void getEmptySession_success() {
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () ->
                vertexAiSessionService
                    .getSession("123", "user", "0", Optional.empty())
                    .blockingGet());
    assertThat(exception).hasMessageThat().contains("Session not found: 0");
  }

  @Test
  public void getAndDeleteSession_success() throws Exception {
    Session session =
        vertexAiSessionService.getSession("123", "user", "1", Optional.empty()).blockingGet();
    assertThat(session.toJson()).isEqualTo(getMockSession().toJson());
    vertexAiSessionService.deleteSession("123", "user", "1").blockingAwait();
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () ->
                vertexAiSessionService
                    .getSession("123", "user", "1", Optional.empty())
                    .blockingGet());
    assertThat(exception).hasMessageThat().contains("Session not found: 1");
  }

  @Test
  public void createSessionAndGetSession_success() throws Exception {
    ConcurrentMap<String, Object> sessionStateMap =
        new ConcurrentHashMap<>(ImmutableMap.of("key", "value"));
    Single<Session> sessionSingle =
        vertexAiSessionService.createSession("123", "user", sessionStateMap, null);
    Session createdSession = sessionSingle.blockingGet();

    assertThat(createdSession.state()).isEqualTo(sessionStateMap);
    assertThat(createdSession.appName()).isEqualTo("123");
    assertThat(createdSession.userId()).isEqualTo("user");
    assertThat(createdSession.lastUpdateTime()).isNotNull();

    String sessionId = createdSession.id();
    Session retrievedSession =
        vertexAiSessionService.getSession("123", "user", sessionId, Optional.empty()).blockingGet();
    assertThat(retrievedSession.toJson()).isEqualTo(createdSession.toJson());
  }

  @Test
  public void listSessions_success() {
    Single<ListSessionsResponse> sessionsSingle =
        vertexAiSessionService.listSessions("123", "user");
    ListSessionsResponse sessions = sessionsSingle.blockingGet();
    ImmutableList<Session> sessionsList = sessions.sessions();
    assertThat(sessionsList).hasSize(2);
    ImmutableList<String> ids = sessionsList.stream().map(Session::id).collect(toImmutableList());
    assertThat(ids).containsExactly("1", "2");
  }

  @Test
  public void listEvents_success() {
    Single<ListEventsResponse> eventsSingle = vertexAiSessionService.listEvents("123", "user", "1");
    ListEventsResponse events = eventsSingle.blockingGet();
    assertThat(events.events()).hasSize(1);
    assertThat(events.events().get(0).id()).isEqualTo("123");
  }

  @Test
  public void appendEvent_success() {
    String userId = "userA";
    Session session = vertexAiSessionService.createSession("987", userId, null, null).blockingGet();
    Event event =
        Event.builder()
            .invocationId("456")
            .author(userId)
            .timestamp(Instant.parse("2024-12-12T12:12:12.123456Z").toEpochMilli())
            .content(Content.fromParts(Part.fromText("appendEvent_success")))
            .build();
    var unused = vertexAiSessionService.appendEvent(session, event).blockingGet();
    ImmutableList<Event> events =
        vertexAiSessionService
            .listEvents(session.appName(), session.userId(), session.id())
            .blockingGet()
            .events();
    assertThat(events).hasSize(1);

    Event retrievedEvent = events.get(0);
    assertThat(retrievedEvent.author()).isEqualTo(userId);
    assertThat(retrievedEvent.content().get().text()).isEqualTo("appendEvent_success");
    assertThat(retrievedEvent.content().get().role()).hasValue("user");
    assertThat(retrievedEvent.invocationId()).isEqualTo("456");
    assertThat(retrievedEvent.timestamp())
        .isEqualTo(Instant.parse("2024-12-12T12:12:12.123456Z").toEpochMilli());
  }

  @Test
  public void listSessions_empty() {
    assertThat(vertexAiSessionService.listSessions("789", "user1").blockingGet().sessions())
        .isEmpty();
  }

  @Test
  public void listEvents_empty() {
    assertThat(vertexAiSessionService.listEvents("789", "user1", "3").blockingGet().events())
        .isEmpty();
  }

  @Test
  public void listEmptySession_success() {
    assertThat(
            vertexAiSessionService
                .getSession("789", "user1", "3", Optional.empty())
                .blockingGet()
                .events())
        .isEmpty();
  }
}
