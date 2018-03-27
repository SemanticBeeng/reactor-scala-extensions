package reactor.core.scala.publisher

trait MonoLike[T] { self: Mono[T] =>

  private def defaultToMonoError[U](t: Throwable): Mono[U] = Mono.error[U](t)

  /** Returns a Mono that mirrors the behavior of the source,
    * unless the source is terminated with an `onError`, in which
    * case the streaming of events fallbacks to an observable
    * emitting a single element generated by the backup function.
    *
    * The created Mono mirrors the behavior of the source
    * in case the source does not end with an error or if the
    * thrown `Throwable` is not matched.
    *
    * See [[onErrorResume]] for the version that takes a
    * total function as a parameter.
    *
    * @param pf - a function that matches errors with a
    *        backup element that is emitted when the source
    *        throws an error.
    */
  final def onErrorRecover[U <: T](pf: PartialFunction[Throwable, U]): Mono[T] = {
    def recover(t: Throwable): Mono[U] = pf.andThen(u => Mono.just(u)).applyOrElse(t, defaultToMonoError)
    onErrorResume(recover)
  }

  /** Returns a Mono that mirrors the behavior of the source,
    * unless the source is terminated with an `onError`, in which case
    * the streaming of events continues with the specified backup
    * sequence generated by the given function.
    *
    * The created Mono mirrors the behavior of the source in
    * case the source does not end with an error or if the thrown
    * `Throwable` is not matched.
    *
    * See [[onErrorResume]] for the version that takes a
    * total function as a parameter.
    *
    * @param pf is a function that matches errors with a
    *        backup throwable that is subscribed when the source
    *        throws an error.
    */
  final def onErrorRecoverWith[U <: T](pf: PartialFunction[Throwable, Mono[U]]): Mono[T] = {
    def recover(t: Throwable): Mono[U] = pf.applyOrElse(t, defaultToMonoError)
    onErrorResume(recover)
  }
}
