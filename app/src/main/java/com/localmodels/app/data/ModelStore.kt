package com.localmodels.app.data

import android.content.Context
import java.io.File

class ModelStore(context: Context) {

    val root: File = File(context.filesDir, "models").apply { mkdirs() }

    fun dir(model: CatalogModel): File = File(root, model.id)

    fun file(model: CatalogModel, file: ModelFile): File = File(dir(model), file.name)

    fun partFile(model: CatalogModel, file: ModelFile): File = File(dir(model), file.name + ".part")

    fun isInstalled(model: CatalogModel): Boolean = model.files.all { f ->
        val target = file(model, f)
        target.isFile && target.length() == f.bytes
    }

    fun installed(model: CatalogModel): Boolean = isInstalled(model)

    fun delete(model: CatalogModel) {
        val target = dir(model).canonicalFile
        require(target.parentFile == root.canonicalFile) { "недопустимый путь модели" }
        check(!target.exists() || target.deleteRecursively()) { "не удалось удалить файлы модели" }
    }

    fun installedModels(catalog: List<CatalogModel>): List<CatalogModel> =
        catalog.filter { isInstalled(it) }
}
