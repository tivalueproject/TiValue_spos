package vsys.state.opcdiffs

import com.google.common.primitives.{Bytes, Ints, Longs}
import com.wavesplatform.state2._
import scorex.account.Address
import scorex.transaction.ValidationError
import scorex.transaction.ValidationError.GenericError
import vsys.contract.{DataEntry, DataType}
import vsys.contract.ExecutionContext

import scala.util.{Left, Right, Try}

object TDBAOpcDiff {

  def deposit(context: ExecutionContext)
             (issuer: DataEntry, amount: DataEntry, tokenIndex: DataEntry): Either[ValidationError, OpcDiff] = {

    if ((issuer.dataType != DataType.Address) || (amount.dataType != DataType.Amount)
      || (tokenIndex.dataType != DataType.Int32)) {
      Left(GenericError("Input contains invalid dataType"))
    } else {
      val contractTokens = context.state.contractTokens(context.contractId.bytes)
      val tokenNumber = Ints.fromByteArray(tokenIndex.data)
      val depositAmount = Longs.fromByteArray(amount.data)
      val tokenID: ByteStr = ByteStr(Bytes.concat(context.contractId.bytes.arr, tokenIndex.data))
      val tokenTotalKey = ByteStr(Bytes.concat(tokenID.arr, Array(1.toByte)))
      val issuerBalanceKey = ByteStr(Bytes.concat(tokenID.arr, issuer.data))
      val currentTotal = context.state.tokenAccountBalance(tokenTotalKey)
      val tokenMaxKey = ByteStr(Bytes.concat(tokenID.arr, Array(0.toByte)))
      val tokenMax = Longs.fromByteArray(context.state.tokenInfo(tokenMaxKey).getOrElse(
        DataEntry(Longs.toByteArray(0), DataType.Amount)).data)
      if (tokenNumber >= contractTokens || tokenNumber < 0) {
        Left(GenericError(s"Token $tokenNumber not exist"))
      } else if (Try(Math.addExact(depositAmount, currentTotal)).isFailure) {
        Left(ValidationError.OverflowError)
      } else if (depositAmount < 0) {
        Left(GenericError("Invalid deposit amount"))
      } else if (depositAmount + currentTotal > tokenMax) {
        Left(GenericError(s"New total ${depositAmount + currentTotal} is larger than the max $tokenMax"))
      } else {
        val a = Address.fromBytes(issuer.data).toOption.get
        Right(OpcDiff(relatedAddress = Map(a -> true),
          tokenAccountBalance = Map(tokenTotalKey -> depositAmount, issuerBalanceKey -> depositAmount)))
      }
    }
  }

  def withdraw(context: ExecutionContext)
              (issuer: DataEntry, amount: DataEntry, tokenIndex: DataEntry): Either[ValidationError, OpcDiff] = {

    if ((issuer.dataType != DataType.Address) || (amount.dataType != DataType.Amount)
      || (tokenIndex.dataType != DataType.Int32)) {
      Left(GenericError("Input contains invalid dataType"))
    } else {
      val contractTokens = context.state.contractTokens(context.contractId.bytes)
      val tokenNumber = Ints.fromByteArray(tokenIndex.data)
      val withdrawAmount = Longs.fromByteArray(amount.data)
      val tokenID: ByteStr = ByteStr(Bytes.concat(context.contractId.bytes.arr, tokenIndex.data))
      val tokenTotalKey = ByteStr(Bytes.concat(tokenID.arr, Array(1.toByte)))
      val issuerBalanceKey = ByteStr(Bytes.concat(tokenID.arr, issuer.data))
      val issuerCurrentBalance = context.state.tokenAccountBalance(issuerBalanceKey)
      if (tokenNumber >= contractTokens || tokenNumber < 0) {
        Left(GenericError(s"Token $tokenNumber not exist"))
      } else if (withdrawAmount > issuerCurrentBalance) {
        Left(GenericError(s"Amount $withdrawAmount is larger than the current balance $issuerCurrentBalance"))
      } else if (withdrawAmount < 0){
        Left(GenericError(s"Invalid withdraw amount $withdrawAmount"))
      }
      else {
        val a = Address.fromBytes(issuer.data).toOption.get
        Right(OpcDiff(relatedAddress = Map(a -> true),
          tokenAccountBalance = Map(tokenTotalKey -> -withdrawAmount, issuerBalanceKey -> -withdrawAmount)
        ))
      }
    }
  }

  def transfer(context: ExecutionContext)
              (sender: DataEntry, recipient: DataEntry, amount: DataEntry,
               tokenIndex: DataEntry): Either[ValidationError, OpcDiff] = {

    if (sender.dataType == DataType.ContractAccount) {
      Left(GenericError("Contract does not support withdraw"))
    } else if (recipient.dataType == DataType.ContractAccount) {
      Left(GenericError("Contract does not support deposit"))
    } else if ((sender.dataType != DataType.Address) || (recipient.dataType != DataType.Address) ||
      (amount.dataType !=  DataType.Amount) || (tokenIndex.dataType != DataType.Int32)) {
      Left(GenericError("Input contains invalid dataType"))
    } else {
      val contractTokens = context.state.contractTokens(context.contractId.bytes)
      val tokenNumber = Ints.fromByteArray(tokenIndex.data)
      val transferAmount = Longs.fromByteArray(amount.data)
      val tokenID: ByteStr = ByteStr(Bytes.concat(context.contractId.bytes.arr, tokenIndex.data))
      val senderBalanceKey = ByteStr(Bytes.concat(tokenID.arr, sender.data))
      val senderCurrentBalance = context.state.tokenAccountBalance(senderBalanceKey)
      val recipientBalanceKey = ByteStr(Bytes.concat(tokenID.arr, recipient.data))
      val recipientCurrentBalance = context.state.tokenAccountBalance(recipientBalanceKey)
      if (tokenNumber >= contractTokens || tokenNumber < 0) {
        Left(GenericError(s"Token $tokenNumber not exist"))
      } else if (transferAmount > senderCurrentBalance) {
        Left(GenericError(s"Amount $transferAmount is larger than the sender balance $senderCurrentBalance"))
      } else if (Try(Math.addExact(transferAmount, recipientCurrentBalance)).isFailure) {
        Left(ValidationError.OverflowError)
      } else if (transferAmount < 0) {
        Left(GenericError(s"Invalid transfer amount $transferAmount"))
      } else {
        val s = Address.fromBytes(sender.data).toOption.get
        val r = Address.fromBytes(recipient.data).toOption.get
        Right(OpcDiff(relatedAddress = Map(s -> true, r -> true),
          tokenAccountBalance = Map(senderBalanceKey -> -transferAmount,
          recipientBalanceKey -> transferAmount)
        ))
      }
    }
  }

  object TDBAType extends Enumeration {
    val DepositTDBA = Value(1)
    val WithdrawTDBA = Value(2)
    val TransferTDBA = Value(3)
  }

  def parseBytes(context: ExecutionContext)
                (bytes: Array[Byte], data: Seq[DataEntry]): Either[ValidationError, OpcDiff] = bytes.head match {
    case opcType: Byte if opcType == TDBAType.DepositTDBA.id && checkInput(bytes,4, data.length) =>
      deposit(context)(data(bytes(1)), data(bytes(2)), data(bytes(3)))
    case opcType: Byte if opcType == TDBAType.WithdrawTDBA.id && checkInput(bytes,4, data.length) =>
      withdraw(context)(data(bytes(1)), data(bytes(2)), data(bytes(3)))
    case opcType: Byte if opcType == TDBAType.TransferTDBA.id && checkInput(bytes,5, data.length) =>
      transfer(context)(data(bytes(1)), data(bytes(2)), data(bytes(3)), data(bytes(4)))
    case _ => Left(GenericError("Wrong TDBA opcode"))
  }

  private def checkInput(bytes: Array[Byte], bLength: Int, dataLength: Int): Boolean = {
    bytes.length == bLength && bytes.tail.max < dataLength && bytes.tail.min >= 0
  }

}