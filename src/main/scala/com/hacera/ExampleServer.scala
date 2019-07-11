// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// (c) 2019 The Unbounded Network LTD

package com.hacera

import java.io.{File, FileWriter}
import java.util.zip.ZipFile

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import com.daml.ledger.api.server.damlonx.Server
import com.daml.ledger.participant.state.index.v1.impl.reference.ReferenceIndexService
import com.daml.ledger.participant.state.v1.ParticipantId
import com.digitalasset.daml.lf.archive.DarReader
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml_lf.DamlLf
import com.digitalasset.daml_lf.DamlLf.Archive
import com.digitalasset.platform.common.util.DirectExecutionContext
import org.slf4j.LoggerFactory

import scala.util.Try

/** The example server is a fully compliant DAML Ledger API server
  * backed by the in-memory reference index and participant state implementations.
  * Not meant for production, or even development use cases, but for serving as a blueprint
  * for other implementations.
  */
object ExampleServer extends App {
  val logger = LoggerFactory.getLogger(this.getClass)

  val config = Cli.parse(args).getOrElse(sys.exit(1))

  // Initialize Fabric connection
  // this will create the singleton instance and establish the connection
  val fabricConn = DAMLKVConnector.get(config.roleProvision, config.roleExplorer)

  // If we only want to provision, exit right after
  if (!config.roleLedger && !config.roleTime && !config.roleExplorer) {
    logger.info("Hyperledger Fabric provisioning complete.")
    System.exit(0)
  }

  // Initialize Akka and log exceptions in flows.
  implicit val system: ActorSystem = ActorSystem("DamlonxExampleServer")
  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system)
      .withSupervisionStrategy { e =>
        logger.error(s"Supervision caught exception: $e")
        Supervision.Stop
      }
  )

  val participantId: ParticipantId = Ref.LedgerString.assertFromString(config.participantId)
  val ledger = new FabricParticipantState(config.roleTime, config.roleLedger, participantId)

  if (config.roleLedger) {
    def archivesFromDar(file: File): List[Archive] = {
      DarReader[Archive]((_, x) => Try(Archive.parseFrom(x)))
        .readArchive(new ZipFile(file))
        .fold(t => throw new RuntimeException(s"Failed to parse DAR from $file", t), dar => dar.all)
    }

    // Parse packages that are already on the chain.
    // Because we are using ReferenceIndexService, we have to re-upload them
    val currentPackages = fabricConn.getPackageList
    currentPackages.foreach { pkgid =>
      val archive = DamlLf.Archive.parseFrom(fabricConn.getPackage(pkgid))
      logger.info(s"Found existing archive ${archive.getHash}.")
      ledger.uploadPackages(List(archive), Some("uploaded by server"))
    }

    // Parse DAR archives given as command-line arguments and upload them
    // to the ledger using a side-channel.
    config.archiveFiles.foreach { f =>
      ledger.uploadPackages(archivesFromDar(f), Some("uploaded by server"))
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread(() => fabricConn.shutdown()))

  if (config.roleLedger) {
    ledger.getLedgerInitialConditions
      .runWith(Sink.head)
      .foreach { initialConditions =>
        val indexService = ReferenceIndexService(
          participantReadService = ledger,
          initialConditions = initialConditions,
          participantId = participantId
        )

        val server = Server(
          serverPort = config.port,
          sslContext = config.tlsConfig.flatMap(_.server),
          indexService = indexService,
          writeService = ledger
        )

        // If port file was provided, write out the allocated server port to it.
        config.portFile.foreach { f =>
          val w = new FileWriter(f)
          w.write(s"${server.port}\n")
          w.close()
        }

        // Add a hook to close the server. Invoked when Ctrl-C is pressed.
        Runtime.getRuntime.addShutdownHook(new Thread(() => server.close()))
      }(DirectExecutionContext)
  }
}
