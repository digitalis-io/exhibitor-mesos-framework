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

import org.junit.Assert._
import org.junit.{Before, Test}
import play.api.libs.json.Json

class ExhibitorServerTest extends MesosTestCase {
  var server: ExhibitorServer = null

  @Before
  override def before() {
    super.before()
    server = new ExhibitorServer("0")
    server.config.cpus = 0
    server.config.mem = 0
  }

  @Test
  def matches() {
    // cpu
    server.config.cpus = 0.5
    assertEquals(None, server.matches(offer(cpus = 0.5)))
    assertEquals(Some("cpus 0.49 < 0.5"), server.matches(offer(cpus = 0.49)))
    server.config.cpus = 0

    // mem
    server.config.mem = 100
    assertEquals(None, server.matches(offer(mem = 100)))
    assertEquals(Some("mem 99.0 < 100.0"), server.matches(offer(mem = 99)))
    server.config.mem = 0

    //port
    assertEquals(None, server.matches(offer(ports = "100")))
    assertEquals(Some("no suitable port"), server.matches(offer(ports = "")))
  }

  @Test
  def matchesHostname() {
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(None, server.matches(offer(hostname = "slave0")))

    // like
    server.constraints.clear()
    server.constraints += "hostname" -> Constraint("like:master")
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(Some("hostname doesn't match like:master"), server.matches(offer(hostname = "slave0")))

    server.constraints.clear()
    server.constraints += "hostname" -> Constraint("like:master.*")
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(None, server.matches(offer(hostname = "master-2")))
    assertEquals(Some("hostname doesn't match like:master.*"), server.matches(offer(hostname = "slave0")))

    // unique
    server.constraints.clear()
    server.constraints += "hostname" -> Constraint("unique")
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(Some("hostname doesn't match unique"), server.matches(offer(hostname = "master"), _ => List("master")))
    assertEquals(None, server.matches(offer(hostname = "master"), _ => List("slave0")))
  }

  @Test
  def matchesAttributes() {
    // like
    server.constraints.clear()
    server.constraints += "rack" -> Constraint("like:1-.*")
    assertEquals(None, server.matches(offer(attributes = "rack=1-1")))
    assertEquals(None, server.matches(offer(attributes = "rack=1-2")))
    assertEquals(Some("rack doesn't match like:1-.*"), server.matches(offer(attributes = "rack=2-1")))

    // unique
    server.constraints.clear()
    server.constraints += "floor" -> Constraint("unique")
    assertEquals(None, server.matches(offer(attributes = "rack=1-1,floor=1")))
    assertEquals(None, server.matches(offer(attributes = "rack=1-1,floor=1"), _ => List("2")))
    assertEquals(Some("floor doesn't match unique"), server.matches(offer(attributes = "rack=1-1,floor=1"), _ => List("1")))
  }

  @Test
  def idFromTaskId() {
    assertEquals("0", ExhibitorServer.idFromTaskId("exhibitor-0-slave0-31000"))
    assertEquals("100", ExhibitorServer.idFromTaskId("exhibitor-100-slave1-32571"))
  }

  @Test
  def json() {
    server.state = ExhibitorServer.Staging
    server.constraints.clear()
    server.constraints += "hostname" -> Constraint("unique")
    server.config.cpus = 1.2
    server.config.mem = 2048
    server.config.hostname = "slave0"
    server.config.sharedConfigChangeBackoff = 5000
    server.config.exhibitorConfig += "zkconfigconnect" -> "192.168.3.1:2181"
    server.config.sharedConfigOverride += "zookeeper-install-directory" -> "/tmp/zookeeper"

    val decoded = Json.toJson(server).as[ExhibitorServer]

    assertEquals(server.state, decoded.state)
    assertEquals(server.constraints, decoded.constraints)
    assertEquals(server.config.cpus, decoded.config.cpus, 0.001)
    assertEquals(server.config.mem, decoded.config.mem, 0.001)
    assertEquals(server.config.hostname, decoded.config.hostname)
    assertEquals(server.config.sharedConfigChangeBackoff, decoded.config.sharedConfigChangeBackoff)
    assertEquals(server.config.exhibitorConfig, decoded.config.exhibitorConfig)
    assertEquals(server.config.sharedConfigOverride, decoded.config.sharedConfigOverride)
  }
}
