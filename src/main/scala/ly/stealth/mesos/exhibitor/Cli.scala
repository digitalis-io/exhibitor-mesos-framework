/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.exhibitor

import java.io.IOException
import java.net.{HttpURLConnection, URL, URLEncoder}

import play.api.libs.json.{JsValue, Json}
import scopt.OptionParser

import scala.io.Source

object Cli {
  def main(args: Array[String]) {
    try {
      exec(args)
    } catch {
      case e: Throwable =>
        System.err.println("Error: " + e.getMessage)
        sys.exit(1)
    }
  }

  def exec(sourceArgs: Array[String]) {
    var args = sourceArgs

    if (args.length == 0) {
      handleHelp()
      println()
      throw new RuntimeException("No command supplied")
    }

    val command = args(0)
    args = args.slice(1, args.length)

    command match {
      case "scheduler" => handleScheduler(args)
      case "add" | "remove" => handleAddRemove(args, command == "add")
      case "start" | "stop" => handleStartStop(args, command == "start")
      case "status" => handleStatus(args)
      case "config" => handleConfig(args)
    }
  }

  def handleHelp() {
    println("Usage: <command>\n")
  }

  def handleScheduler(args: Array[String]) {
    val parser = new OptionParser[Map[String, String]]("Scheduler") {
      opt[String]('m', "master").required().text("Mesos Master addresses.").action { (value, config) =>
        config.updated("master", value)
      }

      opt[String]('a', "api").optional().text("Binding host:port for http/artifact server.").action { (value, config) =>
        config.updated("api", value)
      }

      opt[String]('u', "user").required().text("Mesos user.").action { (value, config) =>
        config.updated("user", value)
      }

      opt[Boolean]('d', "debug").optional().text("Debug mode.").action { (value, config) =>
        config.updated("debug", value.toString)
      }
    }

    parser.parse(args, Map()) match {
      case Some(config) =>
        resolveApi(config.get("api"))

        Config.master = config("master")
        Config.user = config("user")
        config.get("debug").foreach(debug => Config.debug = debug.toBoolean)

        Scheduler.start()
      case None => sys.exit(1)
    }
  }

  def handleAddRemove(args: Array[String], add: Boolean) {
    val parser = new OptionParser[Map[String, String]]("Add/Remove server") {
      opt[String]('i', "id").required().text("Server id.").action { (value, config) =>
        config.updated("id", value)
      }

      opt[String]('c', "cpu").optional().text("CPUs for server.").action { (value, config) =>
        config.updated("cpu", value)
      }

      opt[String]('m', "mem").optional().text("Memory for server.").action { (value, config) =>
        config.updated("mem", value)
      }

      opt[String]('a', "api").optional().text("Binding host:port for http/artifact server.").action { (value, config) =>
        config.updated("api", value)
      }
    }

    parser.parse(args, Map()) match {
      case Some(config) =>
        resolveApi(config.get("api"))

        val server = sendRequest(if (add) "/add" else "/remove", config).as[ExhibitorServer]
        printExhibitorServer(server)
      case None => sys.exit(1)
    }
  }

  def handleStartStop(args: Array[String], start: Boolean) {
    val parser = new OptionParser[Map[String, String]]("Start/Stop server") {
      opt[String]('i', "id").required().text("Server id.").action { (value, config) =>
        config.updated("id", value)
      }

      opt[String]('a', "api").optional().text("Binding host:port for http/artifact server.").action { (value, config) =>
        config.updated("api", value)
      }
    }

    parser.parse(args, Map()) match {
      case Some(config) =>
        resolveApi(config.get("api"))

        val server = sendRequest(if (start) "/start" else "/stop", config).as[ExhibitorServer]
        printExhibitorServer(server)
      case None => sys.exit(1)
    }
  }

  def handleStatus(args: Array[String]) {
    val parser = new OptionParser[Map[String, String]]("Cluster status") {
      opt[String]('a', "api").optional().text("Binding host:port for http/artifact server.").action { (value, config) =>
        config.updated("api", value)
      }
    }

    parser.parse(args, Map()) match {
      case Some(config) =>
        resolveApi(config.get("api"))

        val cluster = sendRequest("/status", config).as[List[ExhibitorServer]]
        printCluster(cluster)
      case None => sys.exit(1)
    }
  }

  def handleConfig(args: Array[String]) {
    val parser = new OptionParser[Map[String, String]]("Configure server") {
      opt[String]('i', "id").required().text("Server id.").action { (value, config) =>
        config.updated("id", value)
      }

      opt[String]('a', "api").optional().text("Binding host:port for http/artifact server.").action { (value, config) =>
        config.updated("api", value)
      }

      opt[String]("configtype").optional().text("Config type to use: s3 or zookeeper.").action { (value, config) =>
        config.updated("configtype", value)
      }

      opt[String]("zkconfigconnect").optional().text("The initial connection string for ZooKeeper shared config storage. E.g: host1:2181,host2:2181...").action { (value, config) =>
        config.updated("zkconfigconnect", value)
      }

      opt[String]("zkconfigzpath").optional().text("The base ZPath that Exhibitor should use. E.g: /exhibitor/config").action { (value, config) =>
        config.updated("zkconfigzpath", value)
      }

      opt[String]("s3credentials").optional().text("Optional credentials to use for s3backup or s3config").action { (value, config) =>
        config.updated("s3credentials", value)
      }

      opt[String]("s3region").optional().text("Optional region for S3 calls (e.g. \"eu-west-1\")").action { (value, config) =>
        config.updated("s3region", value)
      }

      opt[String]("s3config").optional().text("The bucket name and key to store the config (s3credentials may be provided as well). Argument is [bucket name]:[key].").action { (value, config) =>
        config.updated("s3config", value)
      }

      opt[String]("s3configprefix").optional().text("When using AWS S3 shared config files, the prefix to use for values such as locks.").action { (value, config) =>
        config.updated("s3configprefix", value)
      }

      // shared configs
      opt[String]("zookeeper-install-directory").optional().text("Zookeeper install directory shared config").action { (value, config) =>
        config.updated("zookeeper-install-directory", value)
      }

      opt[String]("zookeeper-data-directory").optional().text("Zookeeper data directory shared config").action { (value, config) =>
        config.updated("zookeeper-data-directory", value)
      }
    }

    parser.parse(args, Map()) match {
      case Some(config) =>
        resolveApi(config.get("api"))

        val server = sendRequest("/config", config).as[ExhibitorServer]
        printExhibitorServer(server)
      case None => sys.exit(1)
    }
  }

  private def resolveApi(apiOption: Option[String]) {
    if (Config.api != null) return

    if (apiOption.isDefined) {
      Config.api = apiOption.get
      return
    }

    if (System.getenv("EM_API") != null) {
      Config.api = System.getenv("EM_API")
      return
    }

    throw new IllegalArgumentException("Undefined API url. Please provide either a CLI --api option or EM_API env.")
  }

  private[exhibitor] def sendRequest(uri: String, params: Map[String, String]): JsValue = {
    def queryString(params: Map[String, String]): String = {
      var s = ""
      params.foreach { case (name, value) =>
        if (!s.isEmpty) s += "&"
        s += URLEncoder.encode(name, "utf-8")
        if (value != null) s += "=" + URLEncoder.encode(value, "utf-8")
      }
      s
    }

    val qs: String = queryString(params)
    val url: String = Config.api + (if (Config.api.endsWith("/")) "" else "/") + "api" + uri + "?" + qs

    val connection: HttpURLConnection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    var response: String = null
    try {
      try {
        response = Source.fromInputStream(connection.getInputStream).getLines().mkString
      }
      catch {
        case e: IOException =>
          if (connection.getResponseCode != 200) throw new IOException(connection.getResponseCode + " - " + connection.getResponseMessage)
          else throw e
      }
    } finally {
      connection.disconnect()
    }

    Json.parse(response)
  }

  private def printLine(s: AnyRef = "", indent: Int = 0) = println("  " * indent + s)

  private def printCluster(cluster: List[ExhibitorServer]) {
    printLine("cluster:")
    cluster.foreach(printExhibitorServer(_, 1))
  }

  private def printExhibitorServer(server: ExhibitorServer, indent: Int = 0) {
    printLine("server:", indent)
    printLine(s"id: ${server.id}", indent + 1)
    printLine(s"state: ${server.state}", indent + 1)
    printTaskConfig(server.config, indent + 1)
    printLine()
  }

  private def printTaskConfig(config: TaskConfig, indent: Int) {
    printLine("exhibitor config:", indent)
    config.exhibitorConfig.foreach { case (k, v) =>
      printLine(s"$k: $v", indent + 1)
    }
    printLine("shared config overrides:", indent)
    config.sharedConfigOverride.foreach { case (k, v) =>
      printLine(s"$k: $v", indent + 1)
    }
    printLine(s"cpu: ${config.cpus}", indent)
    printLine(s"mem: ${config.mem}", indent)
  }
}
