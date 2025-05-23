/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.artifacts;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;

/** Base interface for artifact services. */
public interface BaseArtifactService {

  /**
   * Saves an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   * @param artifact the artifact
   * @return the revision ID (version) of the saved artifact.
   */
  Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact);

  /**
   * Gets an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   * @param version Optional version number. If null, loads the latest version.
   * @return the artifact or empty if not found
   */
  Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version);

  /**
   * Lists all the artifact filenames within a session.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @return the list artifact response containing filenames
   */
  Single<ListArtifactsResponse> listArtifactKeys(String appName, String userId, String sessionId);

  /**
   * Deletes an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   */
  Completable deleteArtifact(String appName, String userId, String sessionId, String filename);

  /**
   * Lists all the versions (as revision IDs) of an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @return A list of integer version numbers.
   */
  Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename);
}
