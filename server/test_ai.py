import json
import time
from datetime import datetime, timezone, timedelta
from email.utils import format_datetime

import httpx
import pytest

from .ai import AIClient, AIConfig, AIError, RateLimited, parse_keys, retry_seconds


@pytest.fixture(autouse=True)
def clean(monkeypatch):
    import os
    for name in list(os.environ):
        if name.startswith(('AI_', 'AUDIO_AI_')):
            monkeypatch.delenv(name)
    AIClient._cursor.clear()
    AIClient._cooldown.clear()
    AIClient._retry.clear()


def config(provider='custom', audio=False, keys=('first', 'second')):
    return AIConfig(provider, 'https://api.example.test/v1', 'test-model', keys, audio=audio)


def mock_http(monkeypatch, handler):
    original=httpx.Client
    monkeypatch.setattr('server.ai.httpx.Client', lambda **kwargs: original(transport=httpx.MockTransport(handler)))


def reply():
    return httpx.Response(200,json={'choices':[{'message':{'content':'{"ok":true}'}}]})


def test_key_syntax_and_no_guessing():
    assert parse_keys('')==()
    assert parse_keys('[]')==()
    assert parse_keys('sk-single')==('sk-single',)
    assert parse_keys('["a","b","a"]')==('a','b')
    assert parse_keys('key-with,comma')==('key-with,comma',)
    for value in ('[bad]', '["a",null]', '[""]', '["has whitespace"]'):
        with pytest.raises(ValueError):parse_keys(value)


def test_audio_credentials_never_cross_providers_or_endpoints(monkeypatch):
    monkeypatch.setenv('AI_PROVIDER','openai')
    monkeypatch.setenv('AI_API_KEYS','["main-key"]')
    assert AIConfig.from_env(audio=True).keys==('main-key',)
    assert AIConfig.from_env(audio=True).model=='gpt-audio-1.5'
    monkeypatch.setenv('AUDIO_AI_BASE_URL','https://another.example/v1')
    assert not AIConfig.from_env(audio=True).enabled
    monkeypatch.setenv('AUDIO_AI_PROVIDER','gemini')
    assert not AIConfig.from_env(audio=True).enabled
    monkeypatch.setenv('AUDIO_AI_API_KEYS','audio-key')
    assert AIConfig.from_env(audio=True).keys==('audio-key',)


def test_text_only_preset_does_not_fabricate_audio_support(monkeypatch):
    monkeypatch.setenv('AI_PROVIDER','deepseek')
    monkeypatch.setenv('AI_API_KEYS','test')
    assert AIConfig.from_env().model=='deepseek-flash'
    assert AIConfig.from_env(audio=True).provider=='none'


def test_custom_and_qwen_need_explicit_endpoint(monkeypatch):
    for provider in ('custom','qwen'):
        monkeypatch.setenv('AI_PROVIDER',provider)
        with pytest.raises(ValueError):AIConfig.from_env()
    monkeypatch.setenv('AI_BASE_URL','https://workspace.example/compatible-mode/v1')
    assert AIConfig.from_env().base_url.endswith('/v1')


def test_round_robin_and_openai_audio_payload(monkeypatch):
    seen=[]
    def handler(request):
        seen.append(request);return reply()
    mock_http(monkeypatch,handler)
    client=AIClient(config(audio=True))
    assert client.generate('Listen',{},[('S00','BASE64')])[0]=={'ok':True}
    client.generate('Listen',{})
    assert [r.headers['authorization'] for r in seen]==['Bearer first','Bearer second']
    body=json.loads(seen[0].content)
    assert seen[0].url.path=='/v1/chat/completions'
    assert body['messages'][0]['content'][-1]['input_audio']=={'data':'BASE64','format':'mp3'}
    assert body['modalities']==['text']


def test_gemini_audio_is_native_and_does_not_store(monkeypatch):
    seen=[]
    def handler(request):
        seen.append(request)
        return httpx.Response(200,json={'status':'completed','steps':[{'type':'model_output','content':[{'type':'text','text':'{"ok":true}'}]}]})
    mock_http(monkeypatch,handler)
    AIClient(config('gemini',audio=True)).generate('Listen',{},[('S00','BASE64')])
    body=json.loads(seen[0].content)
    assert body['store'] is False
    assert body['input'][-1]=={'type':'audio','mime_type':'audio/mp3','data':'BASE64'}
    assert seen[0].headers['x-goog-api-key']=='first'


def test_auth_failure_tries_next_key_without_exposing_provider_body(monkeypatch):
    seen=[]
    def handler(request):
        seen.append(request)
        return httpx.Response(401,text='secret server body') if len(seen)==1 else reply()
    mock_http(monkeypatch,handler)
    assert AIClient(config()).generate('test',{})[0]['ok']
    assert len(seen)==2


def test_quota_backoff_shared_across_instances_and_audio(monkeypatch):
    seen=[]
    def handler(request):
        seen.append(request);return httpx.Response(429,headers={'retry-after':'120'})
    mock_http(monkeypatch,handler)
    for client in (AIClient(config()),AIClient(config(audio=True))):
        with pytest.raises(RateLimited):client.generate('test',{})
    assert len(seen)==1


@pytest.mark.parametrize('status',[400,404,500,503])
def test_errors_do_not_change_models_or_dump_credentials(monkeypatch,status):
    seen=[]
    def handler(request):
        seen.append(request);return httpx.Response(status,text='first secret body')
    mock_http(monkeypatch,handler)
    with pytest.raises(AIError) as error:AIClient(config()).generate('test',{})
    assert 'secret' not in str(error.value) and 'first' not in str(error.value)
    assert len(seen)==1


def test_retry_after_http_date():
    until=datetime.now(timezone.utc)+timedelta(seconds=180)
    assert 175<=retry_seconds(format_datetime(until))<=180
    assert retry_seconds('bad')==3600


def test_malformed_model_response_is_safe(monkeypatch):
    mock_http(monkeypatch,lambda _:httpx.Response(200,json={'choices':[{'message':{'content':'secret garbage'}}]}))
    with pytest.raises(AIError) as error:AIClient(config()).generate('test',{})
    assert 'secret garbage' not in str(error.value)
