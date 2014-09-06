package sampleclean.clean.deduplication


import sampleclean.activeml._
import org.apache.spark.SparkContext._
import org.apache.spark.rdd._
import org.apache.spark.sql.Row
import org.apache.spark.mllib.classification.SVMModel
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.linalg.Vectors



case class ActiveLearningStrategy(featureVector: FeatureVector,
                             toPointLabelingContext: (Row, Row) => PointLabelingContext,
                             groupLabelingContext: GroupLabelingContext) {

  def run(labeledInput: RDD[(String, LabeledPoint)], candidatePairs: RDD[(Row, Row)], onUpdateDupCount: RDD[(Row, Row)] => RDD[(String, Int)]) = {

    val pid = utils.randomUUID()
    val candidatePairsWithId = candidatePairs.map((pid, _))

    val labelGetterParameters = CrowdLabelGetterParameters()
    val unlabeledInput = candidatePairsWithId.map(p => (p._1, Vectors.dense(featureVector.toFeatureVector(p._2._1, p._2._2)), toPointLabelingContext(p._2._1, p._2._2)))
    val trainingFuture = ActiveSVMWithSGD.train(
      labeledInput,
      unlabeledInput,
      groupLabelingContext,
      SVMParameters(),
      new CrowdLabelGetter(labelGetterParameters),
      SVMMarginDistanceFilter)

    trainingFuture.onNewModel(processNewModel)

    def processNewModel(model:SVMModel, modelN: Long) {
      val modelLabeledData: RDD[(String, Double)] = unlabeledInput.map(p => (p._1, model.predict(p._2)))
      var mergedLabeledData: RDD[(String, Double)] = modelLabeledData

      val crowdLabeledData = trainingFuture.getLabeledData
      crowdLabeledData match {
        case None => // do nothing
        case Some(crowdLabeledData) => {
          val mergedLabeledData = modelLabeledData.leftOuterJoin(crowdLabeledData).map{
            case (pid, (modelLabel, None)) => (pid, modelLabel)
            case (pid, (modelLabel, Some(crowdLabel))) => (pid, crowdLabel)
          }
        }
      }
      assert(mergedLabeledData.count() == modelLabeledData.count())
      assert(mergedLabeledData.count() == candidatePairsWithId.count())

      val duplicatePairs = mergedLabeledData.filter(_._2 < 0.5).join(candidatePairsWithId).map(_._2._2) // 0: duplicate; 1: non-duplicate
      onUpdateDupCount(duplicatePairs)
    }
  }



}