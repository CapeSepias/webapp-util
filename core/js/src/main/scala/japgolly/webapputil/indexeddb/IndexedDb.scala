package japgolly.webapputil.indexeddb

import japgolly.scalajs.react._
import org.scalajs.dom._
import scala.annotation.elidable
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

final class IndexedDb(raw: IDBFactory) {
  import IndexedDb._
  import IndexedDb.Internals._

  def open(name: DatabaseName): OpenResult =
    _open(raw.open(name.value))

  def open(name: DatabaseName, version: Int): OpenResult =
    _open(raw.open(name.value, version))

  private def _open(rawOpen: => IDBOpenDBRequest[IDBDatabase]): OpenResult =
    callbacks => {

      def create(): IDBOpenDBRequest[IDBDatabase] = {
        val r = rawOpen

        // r.onblocked = callbacks.blocked.toJsFn1

        r.onupgradeneeded = e => {
          val db = new DatabaseInVersionChange(r.result)
          val args = versionChange(db, e)
          callbacks.upgradeNeeded(args).runNow()
        }

        r
      }

      asyncRequest(create()) { r =>
        val rawDb = r.result

        rawDb.onversionchange = e => {
          try {
            val args = versionChange(new DatabaseInVersionChange(rawDb), e)
            callbacks.versionChange(args).runNow()
          } finally {
            // We close the DB at the end of this event on matter what so that other connections don't block and we
            // don't have to handle onblocked events.
            rawDb.close()

            // The onclose event handler is only fired "when the database is unexpectedly closed".
            // Therefore we call it here explicitly.
            // https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/onclose
            callbacks.closed.runNow()
          }
        }

        rawDb.onclose = _ => {
          callbacks.closed.runNow()
        }

        new Database(rawDb, onClose = callbacks.closed)
      }
    }

  def deleteDatabase(name: DatabaseName): AsyncCallback[Unit] =
    asyncRequest_(raw.deleteDatabase(name.value))
}

object IndexedDb {

  def apply(raw: IDBFactory): IndexedDb =
    new IndexedDb(raw)

  def global(): Option[IndexedDb] =
    try {
      window.indexedDB.toOption.map(apply)
    } catch {
      case _: Throwable => None
    }

  final case class DatabaseName(value: String)

  type OpenResult = OpenCallbacks => AsyncCallback[Database]

  // ===================================================================================================================

  import Internals._

  final case class VersionChange(db: DatabaseInVersionChange, oldVersion: Int, newVersion: Option[Int]) {
    def createObjectStore[K, V](ver: Int, defn: ObjectStoreDef[K, V]): Callback =
      db.createObjectStore(defn).when_(oldVersion < ver && newVersion.exists(_ >= ver))
  }

  /** Callbacks to install when opening a DB.
   *
   * Note 1: On `versionChange`, the DB connection will be closed automatically.
   *
   * Note 2: There's no `blocked` handler because we currently don't allow blocking. To quote the idb spec:
   *         if "there are open connections that don’t close in response to a versionchange event, the request will be
   *         blocked until all they close".
   */
  final case class OpenCallbacks(upgradeNeeded: VersionChange => Callback,
                                 versionChange: VersionChange => Callback,
                                 closed       : Callback)

  final case class Error(event: ErrorEvent) extends RuntimeException(
    event.asInstanceOf[js.Dynamic].message.asInstanceOf[js.UndefOr[String]].getOrElse(null)
  ) {

    // Note: allowing .message to be undefined is presumably only required due to use of fake-indexeddb in tests
    val msg: String =
      event.asInstanceOf[js.Dynamic].message.asInstanceOf[js.UndefOr[String]].getOrElse("")

    @elidable(elidable.FINEST)
    override def toString =
      s"IndexedDb.Error($msg)"

    def isStoredDatabaseHigherThanRequested: Boolean = {
      // Chrome: The requested version (1) is less than the existing version (2).
      // Firefox: The operation failed because the stored database is a higher version than the version requested.
      msg.contains("version") && (msg.contains("higher") || msg.contains("less than"))
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  final class Database(raw: IDBDatabase, onClose: Callback) {

    def close: Callback = {
      val actuallyClose =
        Callback(raw.close()).attempt

      // The onclose event handler is only fired "when the database is unexpectedly closed".
      // Therefore we call it here explicitly.
      // https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/onclose
      actuallyClose >> onClose
    }

    val transactionRO: TxnStep1 = new TxnStep1(IDBTransactionMode.readonly)
    val transactionRW: TxnStep1 = new TxnStep1(IDBTransactionMode.readwrite)

    final class TxnStep1 private[Database] (mode: IDBTransactionMode) {
      def apply(stores: ObjectStoreDef[_, _]*): TxnStep2 = {
        val storeArray = new js.Array[String]
        stores.foreach(s => storeArray.push(s.name))
        new TxnStep2(mode, storeArray)
      }
    }

    final class TxnStep2 private[Database] (mode: IDBTransactionMode, stores: js.Array[String]) {

      def apply[A](f: TxnDsl => Txn[A]): AsyncCallback[A] = {
        val x = CallbackTo.pure(f(TxnDsl))
        sync(_ => x)
      }

      def sync[A](dslCB: TxnDsl => CallbackTo[Txn[A]]): AsyncCallback[A] = {

        @inline def startRawTxn(complete: Try[Unit] => Callback) = {
          val txn = raw.transaction(stores, mode)

          txn.onerror = event => {
            complete(Failure(Error(event))).runNow()
          }

          txn.oncomplete = complete(success_).toJsFn1

          txn
        }

        for {
          dsl <- dslCB(TxnDsl).asAsyncCallback

          (awaitTxnCompletion, complete) <- AsyncCallback.promise[Unit].asAsyncCallback

          result <- AsyncCallback.suspend {
            val txn = startRawTxn(complete)
            Txn.interpret(txn, dsl)
          }

          _ <- awaitTxnCompletion

        } yield result
      }

      def async[A](dsl: TxnDsl => AsyncCallback[Txn[A]]): AsyncCallback[A] =
        // Note: This is safer than it looks.
        //       1) This is `TxnDsl => AsyncCallback[Txn[A]]`
        //          and not `TxnDsl => Txn[AsyncCallback[A]]`
        //       2) Everything within Txn is still synchronous and lawful
        //       3) Only one transaction is created (i.e. only one call to `apply`)
        dsl(TxnDsl).flatMap(txnA => apply(_ => txnA))

    } // TxnStep2

    // Convenience methods

    /** Note: insert only */
    def add[K, V](store: ObjectStoreDef.Sync[K, V])(key: K, value: V): AsyncCallback[Unit] =
      transactionRW(store)(_.objectStore(store).flatMap(_.add(key, value)))

    /** Note: insert only */
    def add[K, V](store: ObjectStoreDef.Async[K, V])(key: K, value: V): AsyncCallback[Unit] =
      store.encode(value).flatMap(add(store.sync)(key, _))

    /** aka upsert */
    def put[K, V](store: ObjectStoreDef.Sync[K, V])(key: K, value: V): AsyncCallback[Unit] =
      transactionRW(store)(_.objectStore(store).flatMap(_.put(key, value)))

    /** aka upsert */
    def put[K, V](store: ObjectStoreDef.Async[K, V])(key: K, value: V): AsyncCallback[Unit] =
      store.encode(value).flatMap(put(store.sync)(key, _))

    def get[K, V](store: ObjectStoreDef.Sync[K, V])(key: K): AsyncCallback[Option[V]] =
      transactionRO(store)(_.objectStore(store).flatMap(_.get(key)))

    def get[K, V](store: ObjectStoreDef.Async[K, V])(key: K): AsyncCallback[Option[V]] =
      get(store.sync)(key).flatMap(AsyncCallback.traverseOption(_)(_.decode))

    def getAllKeys[K, V](store: ObjectStoreDef[K, V]): AsyncCallback[Vector[K]] =
      transactionRO(store)(_.objectStore(store.sync).flatMap(_.getAllKeys))

    def getAllValues[K, V](store: ObjectStoreDef.Sync[K, V]): AsyncCallback[Vector[V]] =
      transactionRO(store)(_.objectStore(store).flatMap(_.getAllValues))

    def getAllValues[K, V](store: ObjectStoreDef.Async[K, V]): AsyncCallback[Vector[V]] =
      getAllValues(store.sync).flatMap(AsyncCallback.traverse(_)(_.decode))

    def delete[K, V](store: ObjectStoreDef[K, V])(key: K): AsyncCallback[Unit] =
      transactionRW(store)(_.objectStore(store.sync).flatMap(_.delete(key)))

    def clear[K, V](store: ObjectStoreDef[K, V]): AsyncCallback[Unit] =
      transactionRW(store)(_.objectStore(store.sync).flatMap(_.clear))
  }

  // -------------------------------------------------------------------------------------------------------------------
  final class DatabaseInVersionChange(raw: IDBDatabase) {
    def createObjectStore[K, V](defn: ObjectStoreDef[K, V]): Callback =
      Callback {
        raw.createObjectStore(defn.name)
      }
  }

  // -------------------------------------------------------------------------------------------------------------------
  final class ObjectStore[K, V](val defn: ObjectStoreDef.Sync[K, V]) {
    import defn.{keyCodec, valueCodec}

    /** Note: insert only */
    def add(key: K, value: V): Txn[Unit] = {
      val k = keyCodec.encode(key)
      Txn.Eval(valueCodec.encode(value)).flatMap(Txn.StoreAdd(this, k, _))
    }

    /** aka upsert */
    def put(key: K, value: V): Txn[Unit] = {
      val k = keyCodec.encode(key)
      Txn.Eval(valueCodec.encode(value)).flatMap(Txn.StorePut(this, k, _))
    }

    def get(key: K): Txn[Option[V]] =
      Txn.StoreGet(this, keyCodec.encode(key))

    def getAllKeys: Txn[Vector[K]] =
      Txn.StoreGetAllKeys(this)

    def getAllValues: Txn[Vector[V]] =
      Txn.StoreGetAllVals(this)

    def delete(key: K): Txn[Unit] =
      Txn.StoreDelete(this, keyCodec.encode(key))

    def clear: Txn[Unit] =
      Txn.StoreClear(this)
  }

  // -------------------------------------------------------------------------------------------------------------------
  final class TxnDsl private[IndexedDb] () {

    def pure[A](a: A): Txn[A] =
      eval(CallbackTo.pure(a))

    def delay[A](a: => A): Txn[A] =
      eval(CallbackTo(a))

    def suspend[A](a: => Txn[A]): Txn[A] =
      Txn.Suspend(CallbackTo(a))

    // Sync only. Async not allowed by IndexedDB.
    def eval[A](c: CallbackTo[A]): Txn[A] =
      Txn.Eval(c)

    def unit: Txn[Unit] =
      Txn.unit

    def tailRec[A, B](a: A)(f: A => Txn[Either[A, B]]): Txn[B] =
      Txn.TailRec(a, f)

    def objectStore[K, V](s: ObjectStoreDef.Sync[K, V]): Txn[ObjectStore[K, V]] =
      Txn.GetStore(s)

    @inline def objectStore[K, V](s: ObjectStoreDef.Async[K, V]): Txn[ObjectStore[K, s.Value]] =
      objectStore(s.sync)

    def traverse_[A](as: IterableOnce[A])(f: A => Txn[_]): Txn[Unit] = {
      val it = as.iterator
      if (it.isEmpty)
        unit
      else {
        val first = f(it.next())
        it.foldLeft[Txn[_]](first)(_ >> f(_)) >> unit
      }
    }
  }

  private val TxnDsl = new TxnDsl()

  // -------------------------------------------------------------------------------------------------------------------

  /** Embedded language for safely working with(in) an IndexedDB transaction.
    *
    * This is necessary because whilst all the transaction methods are async, any other type of asynchronicity is not
    * supported and will result in IndexedDB automatically committing and closing the transaction, in which case,
    * further interaction with the transaction will result in a runtime error.
    *
    * Therefore, returning [[AsyncCallback]] from within transactions is dangerous because it allows composition of
    * both kinds of asynchronicity. To avoid this, we use this embedded language and don't publicly expose its
    * interpretation/translation to [[AsyncCallback]]. From the call-site's point of view, a `Txn[A]` is completely
    * opaque.
    *
    * This also has a nice side-effect of ensuring that transaction completion is always awaited because we do it in the
    * transaction functions right after interpretation. Otherwise, the call-sites would always need to remember to do it
    * if live transaction access were exposed.
    *
    * @tparam A The return type.
    */
  sealed trait Txn[A] {
    import Txn._

    final def map[B](f: A => B): Txn[B] =
      Map(this, f)

    final def flatMap[B](f: A => Txn[B]): Txn[B] =
      FlatMap(this, f)

    final def >>[B](f: Txn[B]): Txn[B] =
      FlatMap(this, (_: A) => f)

    final def void: Txn[Unit] =
      FlatMap(this, Txn.toUnit)
  }

  private object Txn {
    final case class Map            [A, B](from: Txn[A], f: A => B)                                      extends Txn[B]
    final case class FlatMap        [A, B](from: Txn[A], f: A => Txn[B])                                 extends Txn[B]
    final case class Eval           [A]   (body: CallbackTo[A])                                          extends Txn[A]
    final case class Suspend        [A]   (body: CallbackTo[Txn[A]])                                     extends Txn[A]
    final case class TailRec        [A, B](a: A, f: A => Txn[Either[A, B]])                              extends Txn[B]
    final case class GetStore       [K, V](defn: ObjectStoreDef.Sync[K, V])                              extends Txn[ObjectStore[K, V]]
    final case class StoreAdd             (store: ObjectStore[_, _], key: IndexedDbKey, value: IDBValue) extends Txn[Unit]
    final case class StorePut             (store: ObjectStore[_, _], key: IndexedDbKey, value: IDBValue) extends Txn[Unit]
    final case class StoreGet       [K, V](store: ObjectStore[K, V], key: IndexedDbKey)                  extends Txn[Option[V]]
    final case class StoreGetAllKeys[K, V](store: ObjectStore[K, V])                                     extends Txn[Vector[K]]
    final case class StoreGetAllVals[K, V](store: ObjectStore[K, V])                                     extends Txn[Vector[V]]
    final case class StoreDelete    [K, V](store: ObjectStore[K, V], key: IndexedDbKey)                  extends Txn[Unit]
    final case class StoreClear           (store: ObjectStore[_, _])                                     extends Txn[Unit]

    val unit = Eval(Callback.empty)
    val toUnit = (_: Any) => unit

    def interpret[A](txn: IDBTransaction, dsl: Txn[A]): AsyncCallback[A] =
      AsyncCallback.suspend {
        val stores = js.Dynamic.literal().asInstanceOf[js.Dictionary[IDBObjectStore]]

        def getStore(s: ObjectStore[_, _]) =
          AsyncCallback.delay(stores.get(s.defn.name).get)

        def interpret[B](dsl: Txn[B]): AsyncCallback[B] =
          dsl match {

            case FlatMap(fa, f) =>
              interpret(fa).flatMap(a => interpret(f(a)))

            case StoreGet(s, k) =>
              getStore(s).flatMap { store =>
                asyncRequest(store.get(k.asJs))(_.result).flatMapSync { result =>
                  if (js.isUndefined(result))
                    CallbackTo.pure(None)
                  else
                    s.defn.valueCodec.decode(result).map(Some(_))
                }
              }

            case Eval(c) =>
              c.asAsyncCallback

            case Suspend(s) =>
              s.asAsyncCallback.flatMap(interpret(_))

            case GetStore(sd) =>
              AsyncCallback.delay {
                val s = txn.objectStore(sd.name)
                stores.put(sd.name, s)
                new ObjectStore(sd)
              }

            case StoreAdd(s, k, v) =>
              getStore(s).flatMap { store =>
                asyncRequest_(store.add(v, k.asJs))
              }

            case StorePut(s, k, v) =>
              getStore(s).flatMap { store =>
                asyncRequest_(store.put(v, k.asJs))
              }

            case Map(fa, f) =>
              interpret(fa).map(f)

            case StoreDelete(s, k) =>
              getStore(s).flatMap { store =>
                asyncRequest_(store.delete(k.asJs))
              }

            case StoreGetAllKeys(s) =>
              import s.defn.keyCodec
              getStore(s).flatMap { store =>
                asyncRequest(store.getAllKeys()) { req =>
                  val rawKeys = req.result
                  Vector.tabulate(rawKeys.length) { i =>
                    val rawKey = rawKeys(i)
                    keyCodec.decode(IndexedDbKey.fromJs(rawKey)).runNow() // safe in asyncRequest onSuccess
                  }
                }
              }

            case StoreGetAllVals(s) =>
              import s.defn.valueCodec
              getStore(s).flatMap { store =>
                asyncRequest(store.getAll()) { req =>
                  val rawVals = req.result
                  Vector.tabulate(rawVals.length) { i =>
                    val rawVal = rawVals(i)
                    valueCodec.decode(rawVal).runNow() // safe in asyncRequest onSuccess
                  }
                }
              }

            case StoreClear(s) =>
              getStore(s).flatMap { store =>
                asyncRequest_(store.clear())
              }

            case TailRec(z, f) =>
              AsyncCallback.tailrec(z)(a => interpret(f(a)))

          } // dsl match

        interpret(dsl)
      }

  } // Txn

  // ===================================================================================================================

  private object Internals {

    val success_ = Success(())

    def asyncRequest_[R <: IDBRequest[Any, _]](act: => R): AsyncCallback[Unit] =
      asyncRequest(act)(_ => ())

    def asyncRequest[R <: IDBRequest[Any, _], A](act: => R)(onSuccess: R => A): AsyncCallback[A] =
      AsyncCallback.promise[A].asAsyncCallback.flatMap { case (promise, complete) =>
        val raw = act

        raw.onerror = event => {
          complete(Failure(Error(event))).runNow()
        }

        raw.onsuccess = _ => {
          complete(Try(onSuccess(raw))).runNow()
        }

        promise
      }

    def versionChange(db: DatabaseInVersionChange, e: IDBVersionChangeEvent): VersionChange =
      VersionChange(db, e.oldVersion.toInt, e.newVersionOption.map(_.toInt))
  }

}
