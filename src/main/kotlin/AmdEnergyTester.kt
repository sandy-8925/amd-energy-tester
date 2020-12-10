import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import org.apache.commons.io.FileUtils
import java.io.File
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

object AmdEnergyTester {
    @JvmStatic
    fun main(args : Array<String>) {
        val amdEnergyHwmonPath = findAmdEnergyHwmonPath()
        if(amdEnergyHwmonPath.isBlank()) {
            println("Error: Unable to find amd_energy hwmon directory. Is the module loaded?")
            return
        }

        val energyInfoRetrievalTask : Callable<EnergyInfo> = RetrieveEnergyInfoTask(amdEnergyHwmonPath)
        val energyInfoConsumer = EnergyInfoMonitor()

        val disposable = Observable.interval(0, 5, TimeUnit.SECONDS)
                                    .map { energyInfoRetrievalTask.call() }
                                    .doOnEach(energyInfoConsumer)
                                    .subscribe()

        Completable.fromRunnable(AvxTurboTask())
            .doOnComplete { disposable.dispose() }
            .blockingAwait()

        if(energyInfoConsumer.failFlag) println("Inconsistent values detected")
        else println("No inconsistencies detected")
    }

    private fun findAmdEnergyHwmonPath(): String {
        val HWMON_BASE_DIR_PATH = FileSystems.getDefault().getPath("/sys/class/hwmon")
        for(hwmonDirPath in Files.newDirectoryStream(HWMON_BASE_DIR_PATH)) {
            val hwmonDirNameFile = File(hwmonDirPath.toFile(), "name")
            val hwmonDriverName = FileUtils.readFileToString(hwmonDirNameFile, Charset.defaultCharset())
                    .replace("[\\n\\t ]".toRegex(), "")
            if(hwmonDriverName.contains("amd_energy")) return hwmonDirPath.toString()
        }
        return ""
    }
}

private class EnergyInfoMonitor : Observer<EnergyInfo> {
    private var prevEnergyInfo : EnergyInfo? = null
    var failFlag : Boolean = false
        private set

    override fun onComplete() {
    }

    override fun onSubscribe(d: Disposable) {
    }

    override fun onNext(energyInfo: EnergyInfo) {
        println("Checking energy readings")
        val temp = prevEnergyInfo
        if(temp == null) {
            prevEnergyInfo = energyInfo
            return
        }

        println("prevEnergyInfo:")
        printValues(temp)

        println("energyInfo:")
        printValues(energyInfo)

        val coreEnergySumDiff = energyInfo.coreEnergySum - temp.coreEnergySum
        val socketEnergyDiff = energyInfo.socketEnergy - temp.socketEnergy
        if(coreEnergySumDiff > socketEnergyDiff)  failFlag = true

        println("coreEnergySumDiff = $coreEnergySumDiff, socketEnergyDiff = $socketEnergyDiff")
        println()

        prevEnergyInfo = energyInfo
    }

    private fun printValues(energyInfo: EnergyInfo) {
        println("coreEnergySum = ${energyInfo.coreEnergySum}, socketEnergy = ${energyInfo.socketEnergy}")
    }

    override fun onError(e: Throwable) {
        e.printStackTrace()
    }
}

private class RetrieveEnergyInfoTask(private val hwmonPath : String) : Callable<EnergyInfo> {
    override fun call(): EnergyInfo {
        val hwmonDirPath = FileSystems.getDefault().getPath(hwmonPath)
        val energyInputsCount = Files.newDirectoryStream(hwmonDirPath, "energy*_input").count()
        var coreEnergySum = 0L
        var socketEnergy = 0L

        for(counter in 1..energyInputsCount) {
            val energyInputFile = File(hwmonPath,"energy${counter}_input")
            val energyLabelFile = File(hwmonPath, "energy${counter}_label")

            val energyLabel = FileUtils.readFileToString(energyLabelFile, Charset.defaultCharset())
            val energyReading = FileUtils.readFileToString(energyInputFile, Charset.defaultCharset())
                                        .replace("[\\n\\t ]".toRegex(), "")
                                        .toLong()
            when {
                energyLabel.contains("socket") -> {
                    socketEnergy = energyReading
                }
                energyLabel.contains("core") -> {
                    coreEnergySum += energyReading
                }
            }
        }

        return EnergyInfo(coreEnergySum, socketEnergy)
    }
}

private data class EnergyInfo(val coreEnergySum : Long, val socketEnergy : Long)

private class AvxTurboTask : Runnable {
    override fun run() {
        val numThreads = Runtime.getRuntime().availableProcessors()
        Runtime.getRuntime()
            .exec("avx-turbo --iters 1000000000 --allow-hyperthreads --spec avx256_fma_t/$numThreads")
            .waitFor()
    }
}