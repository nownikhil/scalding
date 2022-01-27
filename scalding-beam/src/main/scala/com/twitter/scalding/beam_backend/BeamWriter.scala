package com.twitter.scalding.beam_backend

import com.stripe.dagon.Rule
import com.twitter.scalding.Execution.{ToWrite, Writer}
import com.twitter.scalding.typed._
import com.twitter.scalding.{CFuture, CancellationHandler, Config, Execution, ExecutionCounters}
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicLong
import org.apache.beam.sdk.Pipeline
import org.apache.beam.sdk.coders.Coder
import org.apache.beam.sdk.io.FileSystems
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

class BeamWriter(val beamMode: BeamMode) extends Writer {
  private val state = new AtomicLong()

  private val sourceCounter: AtomicLong = new AtomicLong(0L)
  private val iterablePipes: scala.collection.mutable.Map[TypedPipe[_], (Coder[_], String)] =
    scala.collection.mutable.Map.empty

  override def start(): Unit = ()

  override def finished(): Unit = ()

  def getForced[T](conf: Config, initial: TypedPipe[T])(implicit
      cec: ExecutionContext
  ): Future[TypedPipe[T]] = ???

  def getIterable[T](conf: Config, initial: TypedPipe[T])(implicit
      cec: ExecutionContext
  ): Future[Iterable[T]] =
    iterablePipes.get(initial) match {
      case Some((coder, path)) =>
        val c: Coder[T] = coder.asInstanceOf[Coder[T]]
        Future(new Iterable[T] {
          override def iterator: Iterator[T] = {
            // Single dir by default just matches the dir, we need to match files inside
            val matchedResources = FileSystems.`match`(s"$path*").metadata()
            matchedResources.size() match {
              case 0 => Iterator.empty
              case 1 =>
                val resource = matchedResources.get(0).resourceId()
                val inputStream = Channels.newInputStream(FileSystems.open(resource))
                new InputStreamIterator(inputStream, c)
              // We enforce num shards = 1, so exactly one file should exist
              case size => sys.error(s"More than 1 file found. Total: $size")
            }
          }
        })
      case None => sys.error(s"No mapping exists for the TypedPipe: $initial")
    }

  override def execute(conf: Config, writes: List[ToWrite[_]])(implicit
      cec: ExecutionContext
  ): CFuture[(Long, ExecutionCounters)] = {
    import Execution.ToWrite._
    val planner = BeamPlanner.plan(conf, beamMode.sources)
    val phases: Seq[Rule[TypedPipe]] = BeamPlanner.defaultOptimizationRules(conf)
    val optimizedWrites = ToWrite.optimizeWriteBatch(writes, phases)
    val pipeline = Pipeline.create(beamMode.pipelineOptions)

    @tailrec
    def rec(optimizedWrites: List[OptimizedWrite[TypedPipe, _]]): Unit =
      optimizedWrites match {
        case Nil => ()
        case x :: xs =>
          x match {
            case OptimizedWrite(pipe, ToWrite.SimpleWrite(opt, sink)) => {
              val pcoll = planner(opt).run(pipeline)
              beamMode.sink(sink) match {
                case Some(ssink) =>
                  ssink.write(pipeline, conf, pcoll)
                case _ => throw new Exception(s"unknown sink: $sink when writing $pipe")
              }
              rec(xs)
            }
            case OptimizedWrite(pipe, ToWrite.ToIterable(opt)) =>
              val pcoll = planner(opt).run(pipeline)
              val tempLocation = pcoll.getPipeline.getOptions.getTempLocation
              assert(tempLocation != null, "Temp location cannot be null when using toIterableExecution")

              val outputPath = BeamWriter.addPaths(tempLocation, sourceCounter.getAndIncrement().toString)
              new BeamFileIO(outputPath).write(pipeline, conf, pcoll)
              iterablePipes += ((pipe, (pcoll.getCoder, outputPath)))

            //TODO: handle Force
            case _ => ???
          }
      }
    rec(optimizedWrites)
    val result = pipeline.run
    val runId = state.getAndIncrement()
    CFuture(
      Future {
        result.waitUntilFinish()
        (runId, ExecutionCounters.empty)
      },
      CancellationHandler.fromFn { ec =>
        Future { result.cancel(); () }(ec)
      }
    )
  }
}

object BeamWriter {
  // This is manually done because java.nio.File.Paths & java.io.File convert "gs://" to "gs:/"
  def addPaths(basePath: String, dir: String): String =
    if (basePath.endsWith("/")) s"$basePath$dir/"
    else s"$basePath/$dir/"
}
