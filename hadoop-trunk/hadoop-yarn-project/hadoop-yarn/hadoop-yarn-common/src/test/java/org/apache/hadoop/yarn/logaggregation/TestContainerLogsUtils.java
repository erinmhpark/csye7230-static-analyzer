/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.logaggregation;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.logaggregation.filecontroller.LogAggregationFileController;
import org.apache.hadoop.yarn.logaggregation.filecontroller.LogAggregationFileControllerContext;
import org.apache.hadoop.yarn.logaggregation.filecontroller.LogAggregationFileControllerFactory;

/**
 * This class contains several utility functions for log aggregation tests.
 * Any assertion libraries shouldn't be used here because this class is used by
 * multiple modules including MapReduce.
 */
public final class TestContainerLogsUtils {

  private TestContainerLogsUtils() {}

  /**
   * Utility function to create container log file and upload
   * it into remote file system.
   * @param conf the configuration
   * @param fs the FileSystem
   * @param rootLogDir the root log directory
   * @param appId the application id
   * @param containerToContent mapping between container id and its content
   * @param nodeId the nodeId
   * @param fileName the log file name
   * @param user the application user
   * @param deleteRemoteLogDir whether to delete remote log dir.
   * @throws IOException if we can not create log files locally
   *         or we can not upload container logs into RemoteFS.
   */
  public static void createContainerLogFileInRemoteFS(Configuration conf,
      FileSystem fs, String rootLogDir, ApplicationId appId,
      Map<ContainerId, String> containerToContent, NodeId nodeId,
      String fileName, String user, boolean deleteRemoteLogDir)
      throws Exception {
    UserGroupInformation ugi = UserGroupInformation.createRemoteUser(user);
    // create local logs
    List<String> rootLogDirList = new ArrayList<String>();
    rootLogDirList.add(rootLogDir);
    Path rootLogDirPath = new Path(rootLogDir);
    if (fs.exists(rootLogDirPath)) {
      fs.delete(rootLogDirPath, true);
    }
    fs.mkdirs(rootLogDirPath);
    // Make sure the target dir is created. If not, FileNotFoundException is thrown
    fs.getFileStatus(rootLogDirPath);
    Path appLogsDir = new Path(rootLogDirPath, appId.toString());
    if (fs.exists(appLogsDir)) {
      fs.delete(appLogsDir, true);
    }
    fs.mkdirs(appLogsDir);
    // Make sure the target dir is created. If not, FileNotFoundException is thrown
    fs.getFileStatus(appLogsDir);
    createContainerLogInLocalDir(appLogsDir, containerToContent, fs, fileName);
    // upload container logs to remote log dir

    LogAggregationFileControllerFactory factory =
        new LogAggregationFileControllerFactory(conf);
    LogAggregationFileController fileController =
        factory.getFileControllerForWrite();

    Path path = fileController.getRemoteAppLogDir(appId, user);

    if (fs.exists(path) && deleteRemoteLogDir) {
      fs.delete(path, true);
    }
    fs.mkdirs(path);
    // Make sure the target dir is created. If not, FileNotFoundException is thrown
    fs.getFileStatus(path);
    uploadContainerLogIntoRemoteDir(ugi, conf, rootLogDirList, nodeId, appId,
        containerToContent.keySet(), path);
  }

  private static void createContainerLogInLocalDir(Path appLogsDir,
      Map<ContainerId, String> containerToContent, FileSystem fs,
      String fileName) throws IOException {
    for (Map.Entry<ContainerId, String> containerAndContent :
        containerToContent.entrySet()) {
      ContainerId containerId = containerAndContent.getKey();
      String content = containerAndContent.getValue();
      Path containerLogsDir = new Path(appLogsDir, containerId.toString());
      if (fs.exists(containerLogsDir)) {
        fs.delete(containerLogsDir, true);
      }
      fs.mkdirs(containerLogsDir);
      // Make sure the target dir is created. If not, FileNotFoundException is thrown
      fs.getFileStatus(containerLogsDir);
      Writer writer =
          new FileWriter(new File(containerLogsDir.toString(), fileName));
      writer.write(content);
      writer.close();
    }
  }

  private static void uploadContainerLogIntoRemoteDir(UserGroupInformation ugi,
      Configuration configuration, List<String> rootLogDirs, NodeId nodeId,
      ApplicationId appId, Iterable<ContainerId> containerIds, Path appDir)
      throws Exception {
    Path path =
        new Path(appDir, LogAggregationUtils.getNodeString(nodeId));
    LogAggregationFileControllerFactory factory
        = new LogAggregationFileControllerFactory(configuration);
    LogAggregationFileController fileController = factory
        .getFileControllerForWrite();
    try {
      Map<ApplicationAccessType, String> appAcls = new HashMap<>();
      appAcls.put(ApplicationAccessType.VIEW_APP, ugi.getUserName());
      LogAggregationFileControllerContext context
          = new LogAggregationFileControllerContext(
              path, path, true, 1000,
              appId, appAcls, nodeId, ugi);
      fileController.initializeWriter(context);
      for (ContainerId containerId : containerIds) {
        fileController.write(new AggregatedLogFormat.LogKey(containerId),
            new AggregatedLogFormat.LogValue(rootLogDirs, containerId,
                ugi.getShortUserName()));
      }
    } finally {
      fileController.closeWriter();
    }
  }
}
