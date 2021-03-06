package com.wavesplatform.consensus

import cats.implicits._
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.features.FeatureProvider._
import com.wavesplatform.settings.BlockchainSettings
import com.wavesplatform.state.{Blockchain, ByteStr, _}
import scorex.block.Block
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.transaction.ValidationError
import scorex.transaction.ValidationError.GenericError

import scala.concurrent.duration.FiniteDuration

class PoSSelector(blockchain: Blockchain, settings: BlockchainSettings) {

  import PoSCalculator._

  protected def pos(height: Int): PoSCalculator =
    if (fairPosActivated(height)) FairPoSCalculator
    else NxtPoSCalculator

  def consensusData(accountPublicKey: Array[Byte],
                    height: Int,
                    targetBlockDelay: FiniteDuration,
                    refBlockBT: Long,
                    refBlockTS: Long,
                    greatGrandParentTS: Option[Long],
                    currentTime: Long): Either[ValidationError, NxtLikeConsensusBlockData] = {
    val bt = pos(height).calculateBaseTarget(targetBlockDelay.toSeconds, height, refBlockBT, refBlockTS, greatGrandParentTS, currentTime)
    blockchain.lastBlock
      .map(_.consensusData.generationSignature.arr)
      .map(gs => NxtLikeConsensusBlockData(bt, ByteStr(generatorSignature(gs, accountPublicKey))))
      .toRight(GenericError("No blocks in blockchain"))
  }

  def getValidBlockDelay(height: Int, accountPublicKey: Array[Byte], refBlockBT: Long, balance: Long): Either[ValidationError, Long] = {
    val pc = pos(height)

    getHit(height, accountPublicKey)
      .map(pc.calculateDelay(_, refBlockBT, balance))
      .toRight(GenericError("No blocks in blockchain"))
  }

  def validateBlockDelay(height: Int, block: Block, parent: Block, effectiveBalance: Long): Either[ValidationError, Unit] = {
    getValidBlockDelay(height, block.signerData.generator.publicKey, parent.consensusData.baseTarget, effectiveBalance)
      .map(_ + parent.timestamp < block.timestamp)
      .ensure(GenericError(s"Block time ${block.timestamp} less than expected"))(identity)
      .map(_ => ())
  }

  def validateGeneratorSignature(height: Int, block: Block): Either[ValidationError, Unit] = {
    blockchain.lastBlock
      .map(b => generatorSignature(b.consensusData.generationSignature.arr, block.signerData.generator.publicKey))
      .toRight(GenericError("No blocks in blockchain T.T"))
      .ensure(GenericError("Generation signatures doesnot match"))(_ sameElements block.consensusData.generationSignature.arr)
      .map(_ => ())
  }

  def validateBaseTarget(height: Int, block: Block, parent: Block, grandParent: Option[Block]): Either[ValidationError, Unit] = {
    val blockBT = block.consensusData.baseTarget
    val blockTS = block.timestamp

    val expectedBT = pos(height).calculateBaseTarget(
      settings.genesisSettings.averageBlockDelay.toSeconds,
      height,
      parent.consensusData.baseTarget,
      parent.timestamp,
      grandParent.map(_.timestamp),
      blockTS
    )

    Either.cond(
      expectedBT == blockBT,
      (),
      GenericError(s"declared baseTarget $blockBT does not match calculated baseTarget $expectedBT")
    )
  }

  private def getHit(height: Int, accountPublicKey: Array[Byte]): Option[BigInt] = {
    val blockForHit =
      if (fairPosActivated(height) && height > 100) blockchain.blockAt(height - 100)
      else blockchain.lastBlock

    blockForHit.map(b => {
      val genSig = b.consensusData.generationSignature.arr
      hit(generatorSignature(genSig, accountPublicKey))
    })
  }

  private def fairPosActivated(height: Int): Boolean = blockchain.activatedFeaturesAt(height).contains(BlockchainFeatures.FairPoS.id)
}
