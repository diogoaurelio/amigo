package prism

import models.{ AmiId, Bake, Recipe, RecipeId }
import prism.Prism.{ Image, Instance, LaunchConfiguration }
import services.PrismAgents

case class Ami(account: String, id: AmiId)

case class BakeUsage(amiId: AmiId, bake: Bake, viaCopy: Option[Image], instances: Seq[Instance], launchConfigurations: Seq[LaunchConfiguration])

case class RecipeUsage(instances: Seq[Instance], launchConfigurations: Seq[LaunchConfiguration], bakeUsage: Seq[BakeUsage])

object RecipeUsage {

  def noUsage(): RecipeUsage = RecipeUsage(Seq.empty[Instance], Seq.empty[LaunchConfiguration], Seq.empty[BakeUsage])

  def allAmis(amiIds: Iterable[AmiId], amigoAccount: String)(implicit prismAgents: PrismAgents): List[Ami] = {
    val amis = amiIds.map(Ami(amigoAccount, _))

    val copiedAmiImages = prismAgents.copiedImages(amiIds.toSet).values.flatten
    val copiedAmis = copiedAmiImages.map(image => Ami(image.ownerId, image.imageId))

    amis.toList ++ copiedAmis
  }

  def apply(bakes: Iterable[Bake])(implicit prismAgents: PrismAgents): RecipeUsage = {
    val bakedAmiLookupMap = bakes.flatMap(b => b.amiId.map(_ -> b)).toMap
    val bakedAmiIds = bakedAmiLookupMap.keys.toList
    val copiedAmis = prismAgents.copiedImages(bakedAmiIds.toSet).values.flatten
    val copiedAmiIds = copiedAmis.map(_.imageId)

    val amiIds = bakedAmiIds ++ copiedAmiIds
    val instances = prismAgents.allInstances.filter(instance => amiIds.contains(instance.imageId))
    val launchConfigurations = prismAgents.allLaunchConfigurations.filter(lc => amiIds.contains(lc.imageId))
    val amis = (instances.map(_.imageId) ++ launchConfigurations.map(_.imageId)).distinct

    val copiedAmiLookupMap = copiedAmis.map { image => image.imageId -> image }.toMap

    val bakeUsage = amis.map { ami =>
      val maybeDirectBake = bakedAmiLookupMap.get(ami)
      val maybeCopy = copiedAmiLookupMap.get(ami)
      val bake = (maybeDirectBake, maybeCopy) match {
        case (Some(directBake), None) => directBake
        case (None, Some(copy)) => bakedAmiLookupMap(copy.copiedFromAMI)
        case _ => throw new IllegalArgumentException("AMI ID provided neither direct nor copied")
      }
      val amiInstances = instances.filter(_.imageId == ami)
      val amiLc = launchConfigurations.filter(_.imageId == ami)
      BakeUsage(ami, bake, maybeCopy, amiInstances, amiLc)
    }

    RecipeUsage(instances, launchConfigurations, bakeUsage)
  }

  def forAll(recipes: Iterable[Recipe], findBakes: RecipeId => Iterable[Bake])(implicit prismAgents: PrismAgents): Map[Recipe, RecipeUsage] = {
    recipes.map(r => r -> apply(findBakes(r.id))).toMap
  }

}
