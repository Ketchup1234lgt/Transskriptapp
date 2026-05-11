import React, {useState, useEffect, useRef} from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  ActivityIndicator, StyleSheet, Alert, PermissionsAndroid,
} from 'react-native';
import {initWhisper} from 'whisper.rn';
import DocumentPicker from 'react-native-document-picker';
import RNFS from 'react-native-fs';

const MODEL_URL = 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin';
const MODEL_PATH = RNFS.DocumentDirectoryPath + '/ggml-medium-q5_0.bin';

export default function App() {
  const [phase, setPhase] = useState('start');
  const [status, setStatus] = useState('');
  const [progress, setProgress] = useState(0);
  const [transcript, setTranscript] = useState('');
  const whisperRef = useRef(null);

  useEffect(() => {
    checkModel();
  }, []);

  const checkModel = async () => {
    const exists = await RNFS.exists(MODEL_PATH);
    if (exists) {
      await loadModel();
    } else {
      setPhase('download');
      setStatus('Modell noch nicht geladen');
    }
  };

  const requestPermissions = async () => {
    await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE,
      PermissionsAndroid.PERMISSIONS.READ_MEDIA_AUDIO,
    ]);
  };

  const downloadModel = async () => {
    setPhase('loading');
    setStatus('Verbinde...');
    try {
      await RNFS.downloadFile({
        fromUrl: MODEL_URL,
        toFile: MODEL_PATH,
        background: true,
        progress: res => {
          const p = Math.round((res.bytesWritten / res.contentLength) * 100);
          setProgress(p);
          setStatus('Herunterladen ' + p + '%  (' + Math.round(res.bytesWritten/1024/1024) + ' MB)');
        },
      }).promise;
      await loadModel();
    } catch (e) {
      setPhase('download');
      setStatus('Fehler: ' + e.message);
      Alert.alert('Download fehlgeschlagen', e.message);
    }
  };

  const loadModel = async () => {
    setPhase('loading');
    setStatus('Modell initialisieren...');
    try {
      whisperRef.current = await initWhisper({
        filePath: MODEL_PATH,
        useNNAPI: true,
      });
      setPhase('ready');
      setStatus('Bereit');
    } catch (e) {
      setPhase('download');
      setStatus('Ladefehler: ' + e.message);
    }
  };

  const pickAndTranscribe = async () => {
    await requestPermissions();
    try {
      const picked = await DocumentPicker.pickSingle({
        type: [DocumentPicker.types.audio, 'audio/*'],
        copyTo: 'cachesDirectory',
      });
      const filePath = picked.fileCopyUri
        ? decodeURIComponent(picked.fileCopyUri.replace('file://', ''))
        : picked.uri.replace('file://', '');

      setPhase('transcribing');
      setTranscript('');
      setStatus('Transkribiere...');

      const {promise} = whisperRef.current.transcribeFile(filePath, {
        language: 'de',
      });
      const {result} = await promise;
      setTranscript(result.trim());
      setPhase('done');
      setStatus('Fertig');
    } catch (e) {
      if (!DocumentPicker.isCancel(e)) {
        setPhase('ready');
        setStatus('Fehler: ' + e.message);
      }
    }
  };

  const reset = () => {
    setTranscript('');
    setPhase('ready');
    setStatus('Bereit');
  };

  return (
    <View style={s.root}>
      <Text style={s.title}>Transkription</Text>
      <Text style={s.sub}>Whisper Medium · NPU</Text>

      {phase === 'download' && (
        <TouchableOpacity style={s.btn} onPress={downloadModel}>
          <Text style={s.btnTxt}>Modell laden (514 MB, einmalig)</Text>
        </TouchableOpacity>
      )}

      {phase === 'loading' && (
        <View style={s.center}>
          <ActivityIndicator size="large" color="#2d4a3e" />
          <Text style={s.statusTxt}>{status}</Text>
          {progress > 0 && (
            <View style={s.barWrap}>
              <View style={[s.barFill, {width: progress + '%'}]} />
            </View>
          )}
        </View>
      )}

      {(phase === 'ready' || phase === 'done') && (
        <TouchableOpacity style={s.btn} onPress={pickAndTranscribe}>
          <Text style={s.btnTxt}>Audio auswählen</Text>
        </TouchableOpacity>
      )}

      {phase === 'transcribing' && (
        <View style={s.center}>
          <ActivityIndicator size="large" color="#2d4a3e" />
          <Text style={s.statusTxt}>Transkribiere...</Text>
        </View>
      )}

      {!!status && phase !== 'loading' && phase !== 'transcribing' && (
        <Text style={s.statusTxt}>{status}</Text>
      )}

      {!!transcript && (
        <ScrollView style={s.box}>
          <Text style={s.text}>{transcript}</Text>
        </ScrollView>
      )}

      {phase === 'done' && (
        <TouchableOpacity style={s.btnSmall} onPress={reset}>
          <Text style={s.btnSmallTxt}>Neu</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  root: {flex:1, backgroundColor:'#f5f0e8', padding:24, paddingTop:70},
  title: {fontSize:34, fontWeight:'700', color:'#2d4a3e'},
  sub: {fontSize:13, color:'#999', marginBottom:40},
  btn: {backgroundColor:'#2d4a3e', padding:18, borderRadius:12, alignItems:'center', marginBottom:12},
  btnTxt: {color:'#fff', fontSize:16, fontWeight:'600'},
  btnSmall: {marginTop:12, alignItems:'center'},
  btnSmallTxt: {color:'#999', fontSize:14},
  center: {alignItems:'center', marginVertical:20},
  statusTxt: {color:'#888', fontSize:13, textAlign:'center', marginTop:10},
  barWrap: {width:'100%', height:4, backgroundColor:'#ddd', borderRadius:2, marginTop:12},
  barFill: {height:4, backgroundColor:'#2d4a3e', borderRadius:2},
  box: {backgroundColor:'#fff', borderRadius:12, padding:16, marginTop:20, maxHeight:400},
  text: {fontSize:17, lineHeight:30, color:'#1a1a1a'},
});
