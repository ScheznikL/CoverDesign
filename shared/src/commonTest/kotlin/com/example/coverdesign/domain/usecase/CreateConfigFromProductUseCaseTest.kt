package com.example.coverdesign.domain.usecase

import com.example.coverdesign.domain.model.Material
import com.example.coverdesign.domain.model.Product
import com.example.coverdesign.domain.model.OrderCategory
import com.example.coverdesign.domain.model.ProductConfig
import com.example.coverdesign.domain.model.Texture
import com.example.coverdesign.domain.model.pricing.PriceEstimate
import com.example.coverdesign.domain.repo.ProductRepository
import com.example.coverdesign.domain.util.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreateConfigFromProductUseCaseTest {

    private val testProduct = Product.Generic(
        id = "prod-1",
        name = "Test Cover",
        description = "A test product",
        basePrice = 50.0,
        category = OrderCategory.ACCESSORY,
        availableMaterials = listOf(Material.PLA, Material.ABS),
        availableTextures = listOf(Texture("t1", "Matte", null, null)),
        imageUrl = ""
    )

    @Test
    fun invoke_returnsSuccess() = runTest {
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = useCase(testProduct)
        assertIs<Resource.Success<ProductConfig>>(result)
    }

    @Test
    fun invoke_configHasCorrectProductId() = runTest {
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(testProduct))
        assertEquals("prod-1", result.data.productId)
    }

    @Test
    fun invoke_configHasCorrectCategory() = runTest {
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(testProduct))
        assertEquals(OrderCategory.ACCESSORY, result.data.orderCategory)
    }

    @Test
    fun invoke_configUsesFirstAvailableMaterial() = runTest {
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(testProduct))
        assertEquals(Material.PLA, result.data.material)
    }

    @Test
    fun invoke_configStartsWithQuantityOne() = runTest {
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(testProduct))
        assertEquals(1, result.data.quantity)
    }

    @Test
    fun invoke_configUsesFirstAvailableTexture() = runTest {
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(testProduct))
        assertEquals("t1", result.data.texture.id)
    }

    @Test
    fun invoke_configHasNonBlankId() = runTest {
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(testProduct))
        assertTrue(result.data.id.isNotBlank())
    }

    @Test
    fun invoke_saveConfigCalledWithGeneratedConfig() = runTest {
        val repo = FakeProductRepository()
        val useCase = CreateConfigFromProductUseCase(repo)
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(testProduct))
        assertNotNull(repo.lastSavedConfig)
        assertEquals(result.data.id, repo.lastSavedConfig!!.id)
    }

    @Test
    fun invoke_fallsBackToPlaWhenNoMaterialsAvailable() = runTest {
        val productNoMaterials = testProduct.copy(availableMaterials = emptyList())
        val useCase = CreateConfigFromProductUseCase(FakeProductRepository())
        val result = assertIs<Resource.Success<ProductConfig>>(useCase(productNoMaterials))
        assertEquals(Material.PLA, result.data.material)
    }
}

private class FakeProductRepository : ProductRepository {

    var lastSavedConfig: ProductConfig? = null

    override suspend fun saveConfig(config: ProductConfig) {
        lastSavedConfig = config
    }

    override suspend fun getCatalog(): Resource<List<Product>> = Resource.Success(emptyList())

    override suspend fun getProduct(id: String): Resource<Product> =
        Resource.Error("not found")

    override fun getSavedConfig(): Flow<ProductConfig?> = flowOf(null)

    override suspend fun calculatePrice(config: ProductConfig): Resource<PriceEstimate> =
        Resource.Error("not implemented")

    override suspend fun getConfigsForProduct(productId: String): List<ProductConfig> = emptyList()

    override suspend fun getConfigById(configId: String): ProductConfig? = null
}
