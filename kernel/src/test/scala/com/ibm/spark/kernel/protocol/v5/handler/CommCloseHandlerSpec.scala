/*
 * Copyright 2014 IBM Corp.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ibm.spark.kernel.protocol.v5.handler

import java.util.UUID

import akka.actor.{Props, ActorRef, ActorSystem}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import com.ibm.spark.kernel.protocol.v5
import com.ibm.spark.kernel.protocol.v5.content.{ClearOutput, CommClose}
import com.ibm.spark.kernel.protocol.v5.kernel.ActorLoader
import com.ibm.spark.kernel.protocol.v5.{SystemActorType, KMBuilder}
import com.ibm.spark.comm.{CommRegistrar, CommWriter, CommCallbacks, CommStorage}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSpecLike, Matchers}
import org.mockito.Mockito._
import org.mockito.Matchers._

import scala.concurrent.duration._

class CommCloseHandlerSpec extends TestKit(
  ActorSystem("CommCloseHandlerSpec")
) with ImplicitSender with FunSpecLike with Matchers with MockitoSugar
  with BeforeAndAfter
{
  private val TestCommId = UUID.randomUUID().toString
  private val TestTargetName = "some test target"

  private var kmBuilder: KMBuilder = _
  private var spyCommStorage: CommStorage = _
  private var mockCommCallbacks: CommCallbacks = _
  private var mockCommRegistrar: CommRegistrar = _
  private var mockActorLoader: ActorLoader = _
  private var commCloseHandler: ActorRef = _
  private var kernelMessageRelayProbe: TestProbe = _
  private var statusDispatchProbe: TestProbe = _

  before {
    kmBuilder = KMBuilder()
    mockCommCallbacks = mock[CommCallbacks]
    spyCommStorage = spy(new CommStorage())
    mockCommRegistrar = mock[CommRegistrar]

    mockActorLoader = mock[ActorLoader]

    commCloseHandler = system.actorOf(Props(
      classOf[CommCloseHandler],
      mockActorLoader, mockCommRegistrar, spyCommStorage
    ))

    // Used to intercept responses
    kernelMessageRelayProbe = TestProbe()
    when(mockActorLoader.load(SystemActorType.KernelMessageRelay))
      .thenReturn(system.actorSelection(kernelMessageRelayProbe.ref.path.toString))

    // Used to intercept busy/idle messages
    statusDispatchProbe = new TestProbe(system)
    when(mockActorLoader.load(SystemActorType.StatusDispatch))
      .thenReturn(system.actorSelection(statusDispatchProbe.ref.path.toString))
  }

  describe("CommCloseHandler") {
    describe("#process") {
      it("should execute close callbacks if the id is registered") {
        // Mark our id as registered
        doReturn(Some(mockCommCallbacks)).when(spyCommStorage)
          .getCommIdCallbacks(TestCommId)

        // Send a comm_open message with the test target
        commCloseHandler ! kmBuilder
          .withHeader(CommClose.toTypeString)
          .withContentString(CommClose(TestCommId, v5.Data()))
          .build

        // Should receive a busy and an idle message
        statusDispatchProbe.receiveN(2, 200.milliseconds)

        // Verify that the msg callbacks were triggered along the way
        verify(mockCommCallbacks).executeCloseCallbacks(
          any[CommWriter], any[v5.UUID], any[v5.Data])
      }

      it("should not execute close callbacks if the id is not registered") {
        // Mark our target as not registered
        doReturn(None).when(spyCommStorage).getCommIdCallbacks(TestCommId)

        // Send a comm_msg message with the test id
        commCloseHandler ! kmBuilder
          .withHeader(CommClose.toTypeString)
          .withContentString(CommClose(TestCommId, v5.Data()))
          .build

        // Should receive a busy and an idle message
        statusDispatchProbe.receiveN(2, 200.milliseconds)

        // Verify that the msg callbacks were NOT triggered along the way
        verify(mockCommCallbacks, never()).executeCloseCallbacks(
          any[CommWriter], any[v5.UUID], any[v5.Data])
      }

      it("should do nothing if there is a parsing error") {
        // Send a comm_open message with an invalid content string
        commCloseHandler ! kmBuilder
          .withHeader(CommClose.toTypeString)
          .withContentString(ClearOutput(_wait = true))
          .build

        // TODO: Is there a better way to test for this without an upper time
        //       limit? Is there a different logical approach?
        kernelMessageRelayProbe.expectNoMsg(200.milliseconds)
      }
    }
  }
}