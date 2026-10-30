package com.rumi.hermesvoice.core.live

import com.rumi.hermesvoice.core.headset.*

/** Pair OS-exposed SOURCE with the selected SINK, never by equal device IDs or a display name.
 * Ambiguous/absent capabilities mean unavailable, not proof the physical accessory has no mic. */
object LiveInputPolicy {
    fun headset(output:AudioEndpoint,inputs:List<AudioEndpoint>,outputs:List<AudioEndpoint>):AudioEndpoint? {
        if(output.type==EndpointType.WIRED_HEADPHONES)return null
        val family=HeadsetClassifier.outputFamily(output.type)?:return null
        val candidates=inputs.filter{HeadsetClassifier.inputFamily(it.type)==family}
        if(output.address.isNotBlank()) {
            val exact=candidates.filter{it.address==output.address}
            if(exact.isNotEmpty())return exact.singleOrNull()
            if(candidates.any{it.address.isNotBlank()})return null
        }
        // Blank wired/USB/platform addresses have no attachment correlation. Only a unique family is safe.
        if(outputs.count{HeadsetClassifier.outputFamily(it.type)==family}!=1)return null
        return candidates.singleOrNull()?.takeIf{output.address.isBlank()||it.address.isBlank()}
    }
}
