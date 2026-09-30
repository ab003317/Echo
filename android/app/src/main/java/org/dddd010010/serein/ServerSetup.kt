package org.dddd010010.serein

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable fun ServerSetup() {
    var address by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    Surface(color=Night,modifier=Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    BrandMark(Modifier.size(64.dp))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={
                        var activity=context
                        while(activity is android.content.ContextWrapper && activity !is android.app.Activity) activity=activity.baseContext
                        (activity as? android.app.Activity)?.let { AppLocale.choose(it,if(AppLocale.language=="en")"zh-Hant" else "en") }
                    },enabled=!busy) { Text(if(AppLocale.language=="en")"繁體中文" else "English") }
                }
                Spacer(Modifier.height(48.dp))
                Text("Echo",fontFamily=EchoLatinTitle,fontSize=48.sp,fontWeight=FontWeight.Medium)
                Text(tr(R.string.setup_title),fontSize=24.sp,modifier=Modifier.padding(top=12.dp,bottom=12.dp))
                Text(tr(R.string.setup_description),color=Muted,lineHeight=24.sp)
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(value=address,onValueChange={address=it;error=""},enabled=!busy,
                    label={Text(tr(R.string.ui_server_address))},placeholder={Text("https://music.example.com/music")},
                    singleLine=true,isError=error.isNotBlank(),modifier=Modifier.fillMaxWidth())
                Text(error.ifBlank{tr(R.string.setup_address_hint)},color=if(error.isBlank())Muted else MaterialTheme.colorScheme.error,
                    fontSize=13.sp,lineHeight=20.sp,modifier=Modifier.padding(top=12.dp))
                Button(onClick={scope.launch{
                    busy=true;error=""
                    try { Library.changeServer(address) }
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){error=tr(R.string.setup_connection_failed)}
                    finally{busy=false}
                }},enabled=!busy && address.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=28.dp).height(52.dp)) {
                    if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                    else Text(tr(R.string.ui_connect))
                }
            }
        }
    }
}
