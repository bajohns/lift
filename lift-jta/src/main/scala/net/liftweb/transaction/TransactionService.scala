/*
 * Copyright 2009 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

package net.liftweb.transaction

import javax.naming.{NamingException, Context, InitialContext}
import javax.transaction.{
  Transaction,
  UserTransaction,
  TransactionManager,
  Status,
  Synchronization,
  RollbackException,
  SystemException,
  TransactionRequiredException
}
import javax.persistence.{
  EntityManager,
  EntityManagerFactory,
  NonUniqueResultException,
  NoResultException
}

import net.liftweb.util.Log

/**
 * Transaction service.
 */
trait TransactionService {
  def getTransactionManager: TransactionManager  
  def getEntityManagerFactory: EntityManagerFactory
}

/**
 * Atomikos implementation of the transaction service trait.
 */
class AtomikosTransactionService extends TransactionService with TransactionProtocol {
  import com.atomikos.icatch.jta.{J2eeTransactionManager, J2eeUserTransaction}
  import com.atomikos.icatch.config.{TSInitInfo, UserTransactionService, UserTransactionServiceImp}

  val JTA_TRANSACTION_TIMEOUT = 60
  private val txService: UserTransactionService = new UserTransactionServiceImp
  private val info: TSInitInfo = txService.createTSInitInfo

  protected[liftweb] val tm =
    try {
      txService.init(info)
      val tm: TransactionManager = new J2eeTransactionManager
      tm.setTransactionTimeout(JTA_TRANSACTION_TIMEOUT)
      tm
    } catch {
      case e => throw new SystemException("Could not create a new Atomikos J2EE Transaction Manager, due to: " + e.toString)
    }

  def getTransactionManager: TransactionManager = tm

  def getEntityManagerFactory: EntityManagerFactory = null

  // TODO: gracefully shutdown of the TM
  //txService.shutdown(false)
}

/**
 * <p>
 * Trait that implements a JTA transaction service that obeys the transaction semantics defined
 * in the transaction attribute types for the transacted methods according to the EJB 3 draft specification.
 * The aspect handles UserTransaction, TransactionManager instance variable injection thru @javax.ejb.Inject
 * (name subject to change as per EJB 3 spec) and method transaction levels thru @javax.ejb.TransactionAttribute.
 * </p>
 *
 * <p>
 * This trait should be inherited to implement the getTransactionManager() method that should return a concrete
 * javax.transaction.TransactionManager implementation (from JNDI lookup etc).
 * </p>
 * <p>
 * <h3>Transaction attribute semantics</h3>
 * (From http://www.kevinboone.com/ejb-transactions.html)
 * </p>
 * <p>
 * <h4>Required</h4>
 * 'Required' is probably the best choice (at least initially) for an EJB method that will need to be transactional. In this case, if the  method's caller is already part of a transaction, then the EJB method does not create a new transaction, but continues in the same transaction as its caller. If the caller is not in a transaction, then a new transaction is created for the EJB method. If something happens in the EJB that means that a rollback is required, then the extent of the rollback will include everything done in the EJB method, whatever the condition of the caller. If the caller was in a transaction, then everything done by the caller will be rolled back as well. Thus the 'required' attribute ensures that any work done by the EJB will be rolled back if necessary, and if the caller requires a rollback that too will be rolled back.
 * </p>
 * <p>
 * <h4>RequiresNew</h4>
 * 'RequiresNew' will be appropriate if you want to ensure that the EJB method is rolled back if necessary, but you don't want the rollback to propogate back to the caller. This attribute results in the creation of a new transaction for the method, regardless of the transactional state of the caller. If the caller was operating in a transaction, then its transaction is suspended until the EJB method completes. Because a new transaction is always created, there may be a slight performance penalty if this attribute is over-used.
 * </p>
 * <p>
 * <h4>Mandatory</h4>
 * With the 'mandatory' attribute, the EJB method will not even start unless its caller is in a transaction. It will throw a <code>TransactionRequiredException</code> instead. If the method does start, then it will become part of the transaction of the caller. So if the EJB method signals a failure, the caller will be rolled back as well as the EJB.
 * </p>
 * <p>
 * <h4>Supports</h4>
 * With this attribute, the EJB method does not care about the transactional context of its caller. If the caller is part of a transaction, then the EJB method will be part of the same transaction. If the EJB method fails, the transaction will roll back. If the caller is not part of a transaction, then the EJB method will still operate, but a failure will not cause anything to roll back. 'Supports' is probably the attribute that leads to the fastest method call (as there is no transactional overhead), but it can lead to unpredicatable results. If you want a method to be isolated from transactions, that is, to have no effect on the transaction of its caller, then use 'NotSupported' instead.
 * </p>
 * <p>
 * <h4>NotSupported</h4>
 * With the 'NotSupported' attribute, the EJB method will never take part in a transaction. If the caller is part of a transaction, then the caller's transaction is suspended. If the EJB method fails, there will be no effect on the caller's transaction, and no rollback will occur. Use this method if you want to ensure that the EJB method will not cause a rollback in its caller. This is appropriate if, for example, the method does something non-essential, such as logging a message. It would not be helpful if the failure of this operation caused a transaction rollback.
 * </p>
 * <p>
 * <h4>Never</h4>
 * The 'NotSupported'' attribute will ensure that the EJB method is never called by a transactional caller. Any attempt to do so will result in a <code>RemoteException</code> being thrown. This attribute is probably less useful than `NotSupported', in that NotSupported will assure that the caller's transaction is never affected by the EJB method (just as `Never' does), but will allow a call from a transactional caller if necessary.
 * </p>
 */
trait TransactionProtocol {

  /**
   * Wraps body in a transaction with REQUIRED semantics.
   */
  def withTxRequired[T](body: => T): T = {
    val tm = TransactionContext.getTransactionManager
    if (!isExistingTransaction(tm)) {
      tm.begin
      joinTransaction
      try {
        body
      } catch {
        case e: RuntimeException => handleException(tm, e)
      } finally {
        commitOrRollBack(tm)
      }
    } else body
  }

  // FIXME: FIX THE CONTEXT STACK MANAGEMENT FOR REQUIRES_NEW (use withContext(newContext) {...}) !!!!!!!!!!!!!!!!!!!!!!!!!!
  /**
   * Wraps body in a transaction with REQUIRES_NEW semantics.
   */
  def withTxRequiresNew[T](body: => T): T = {
    val tm = TransactionContext.getTransactionManager
    if (isExistingTransaction(tm)) {
      Log.debug("Suspend TX")
      storeInThreadLocal(tm.suspend)
      // FIXME: suspend the current EntityManager and create a new one + reset at the end of the method???
    } else {
      TransactionContext.getEntityManager
    }
    tm.begin
    joinTransaction
    try {
      body
    } catch {
      case e: RuntimeException => handleException(tm, e)
    } finally {
      commitOrRollBack(tm)
      fetchFromThreadLocal match {
        case None => throw new IllegalStateException("Expected a suspended transaction")
        case Some(suspendedTx) =>
          Log.debug("Resuming TX")
          tm.resume(suspendedTx)
          storeInThreadLocal(null)
      }
    }
  }

  /**
   * Wraps body in a transaction with SUPPORTS semantics.
   */
  def withTxSupports[T](body: => T): T = {
    // attach to current if exists else skip -> do nothing
    body
  }

  /**
   * Wraps body in a transaction with MANDATORY semantics.
   */
  def withTxMandatory[T](body: => T): T = {
    if (!isExistingTransaction(TransactionContext.getTransactionManager)) throw new TransactionRequiredException("No active TX at method with TX type set to MANDATORY")
    body
  }

  /**
   * Wraps body in a transaction with NEVER semantics.
   */
  def withTxNever[T](body: => T): T = {
    if (isExistingTransaction(TransactionContext.getTransactionManager)) throw new SystemException("Detected active TX at method with TX type set to NEVER")
    body
  }

  /**
   * Wraps body in a transaction with NOT_SUPPORTED semantics.
   */
  def withTxNotSupported[T](body: => T): T = {
    val tm = TransactionContext.getTransactionManager
    if (isExistingTransaction(tm)) {
      Log.debug("Suspend TX")
      storeInThreadLocal(tm.suspend)
    }
    try {
      body
    } catch {
      case e: RuntimeException => handleException(tm, e)
    } finally {
      fetchFromThreadLocal match {
        case None => throw new IllegalStateException("Expected a suspended transaction")
        case Some(suspendedTx) =>
          Log.debug("Resuming TX")
          tm.resume(suspendedTx)
          storeInThreadLocal(null)
      }
    }
  }

  protected def handleException(tm: TransactionManager, e: Exception) = {
    if (isExistingTransaction(tm)) {
      // Do not roll back in case of NoResultException or NonUniqueResultException
      if (!e.isInstanceOf[NoResultException] &&
          !e.isInstanceOf[NonUniqueResultException]) {
        Log.debug("Setting TX to ROLLBACK_ONLY, due to: %s", e)
        tm.setRollbackOnly
      }
    }
    throw e
  }

  protected def commitOrRollBack(tm: TransactionManager) = {
    if (isExistingTransaction(tm)) {
      if (isRollbackOnly(tm)) {
        Log.debug("Rolling back TX marked as ROLLBACK_ONLY")
        tm.rollback
      } else {
        Log.debug("Committing TX")
        tm.commit
      }
    }
  }

  //--- Helper methods

  /**
   * Checks if a transaction is an existing transaction.
   *
   * @param tm the transaction manager
   * @return boolean
   */
  protected def isExistingTransaction(tm: TransactionManager): Boolean = tm.getStatus != Status.STATUS_NO_TRANSACTION

  /**
   * Checks if current transaction is set to rollback only.
   *
   * @param tm the transaction manager
   * @return boolean
   */
  protected def isRollbackOnly(tm: TransactionManager): Boolean = tm.getStatus == Status.STATUS_MARKED_ROLLBACK

  private def joinTransaction = {
    val em = TransactionContext.getEntityManager
    val tm = TransactionContext.getTransactionManager
    tm.getTransaction.registerSynchronization(new EntityManagerSynchronization(em, tm, false))
    em.joinTransaction // join JTA transaction
  }

  /**
   * A ThreadLocal variable where to store suspended TX and enable pay as you go
   * before advice - after advice data sharing in a specific case of requiresNew TX
   */
  private val suspendedTx = new ThreadLocal[Transaction] {
    override def initialValue = null
  }

  private def storeInThreadLocal(tx: Transaction) = suspendedTx.set(tx)

  private def fetchFromThreadLocal: Option[Transaction] = {
    if (suspendedTx != null && suspendedTx.get() != null) Some(suspendedTx.get.asInstanceOf[Transaction])
    else None
  }
}

