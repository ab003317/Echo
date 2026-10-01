@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Uses Material's drag, fling and snap behavior while measuring the actual title/search height. */
@Composable fun CollapsingHeader(
    behavior:TopAppBarScrollBehavior,
    modifier:Modifier=Modifier,
    content:@Composable ColumnScope.()->Unit
){
    val hidden by remember(behavior.state){derivedStateOf{behavior.state.collapsedFraction>=.999f}}
    val visibility=if(hidden)Modifier.clearAndSetSemantics{}.focusProperties{canFocus=false} else Modifier
    Layout(content={Column(Modifier.fillMaxWidth(),content=content)},modifier=modifier.fillMaxWidth().clipToBounds().then(visibility)){measurables,constraints->
        val header=measurables.single().measure(constraints.copy(minHeight=0,maxHeight=Constraints.Infinity))
        val limit=-header.height.toFloat()
        if(behavior.state.heightOffsetLimit!=limit){
            val collapsed=behavior.state.collapsedFraction
            behavior.state.heightOffsetLimit=limit
            behavior.state.heightOffset=limit*collapsed
        }
        val visible=(header.height+behavior.state.heightOffset).roundToInt().coerceIn(0,header.height)
        layout(header.width,visible){header.placeRelative(0,visible-header.height)}
    }
}
