import com.meta.spatial.toolkit.MediaPanelShapeOptions
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelConfigOptions

// Custom class implementing MediaPanelShapeOptions for Dome
class DomeMediaPanelShapeOptions(
    private val domeMesh: SceneMesh,
    private val radius: Float  // Accept radius to calculate width and height
) : MediaPanelShapeOptions {

    // Manually set the width and height based on the dome's radius
    val width: Float = radius * 2  // Diameter of the dome
    val height: Float = radius  // For a hemisphere, the height is the radius

    // Implement the abstract 'applyTo' method
    override fun applyTo(options: PanelConfigOptions) {
        // We don't need to access sceneObject here anymore. This method is just for panel settings.
    }
}