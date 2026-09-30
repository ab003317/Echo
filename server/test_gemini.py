import json
import httpx
import pytest
from server.gemini import Gemini, TasteProfile, analysis_input, grounded_profile, MUSIC_UNKNOWN, POLICY_VERSION

PROFILE={'observations':[],'artists':[{'artist':'A','weight':.6}],
         'queries':['A official audio','B official audio','C official audio'],'exploration':.3}


def test_validated_structured_profile(monkeypatch):
    def generated(self,prompt,schema):
        assert 'titleSemanticsAvailable' in prompt
        return PROFILE, 'test-model'
    monkeypatch.setattr('server.gemini.AIClient.generate', generated)
    data,model=Gemini().analyze({'today':[]})
    assert data['queries']==PROFILE['queries'] and model=='test-model'
    assert data['artists']==[] and MUSIC_UNKNOWN in data['summary']
    assert data['policyVersion']==POLICY_VERSION


def test_keys_are_deduplicated_and_profile_requires_exploration(monkeypatch):
    monkeypatch.setenv('AI_API_KEYS','["test-key-one","test-key-one"]')
    assert Gemini().keys()==['test-key-one']
    with pytest.raises(ValueError):TasteProfile.model_validate({**PROFILE,'exploration':0})


def test_titles_and_unverified_tags_never_reach_analysis_input():
    row={'track':'abc123','title':'Heavy Metal Sleep','artist':'A','genre':'metal','lyrics':'sad words',
         'cover':'dark cover','seconds':30,'effectivePlays':1}
    safe=analysis_input({'today':[row],'recent':[row],'legacy':[row],'favorites':[row]})
    for period in ('today','recent','legacy','favorites'):
        assert not {'title','genre','lyrics','cover'} & set(safe[period][0])
        assert safe[period][0]['track']=='abc123' and safe[period][0]['seconds']==30
    assert safe['musicEvidence']['audioAnalyzed'] is False
    assert safe['musicEvidence']['verifiedMusicalFeatures']==[]


def test_model_prose_cannot_turn_title_into_genre_claim():
    evidence={'today':[{'track':'abc123','title':'夜','artist':'A','seconds':300,'effectivePlays':2}]}
    raw=TasteProfile.model_validate({**PROFILE,'summary':'喜欢夜晚氛围的慢速钢琴','focus':['钢琴'],
        'observations':[{'kind':'repeated','period':'today','track':'abc123'}]}).model_dump()
    profile=grounded_profile(raw,evidence)
    assert '《夜》有 2 次有效聆听' in profile['summary']
    assert '慢速钢琴' not in profile['summary'] and profile['focus']==['重复聆听']
    assert MUSIC_UNKNOWN in profile['summary']
    assert profile['artists']==[{'artist':'A','weight':.6}]


@pytest.mark.parametrize('observation',[
    {'kind':'repeated','period':'today','track':'abc123'},
    {'kind':'completed','period':'today','track':'abc123'},
    {'kind':'early_skip','period':'today','track':'abc123'},
    {'kind':'favorite','period':'today','track':'abc123'},
    {'kind':'listened','period':'today','track':'nonexistent'},
])
def test_unbacked_observations_are_rejected(observation):
    evidence={'today':[{'track':'abc123','seconds':30,'effectivePlays':1}]}
    with pytest.raises(ValueError):grounded_profile({**PROFILE,'observations':[observation]},evidence)


def test_artist_reputation_and_one_skip_do_not_establish_preference():
    evidence={'today':[{'track':'abc123','artist':'A','seconds':1,'earlySkips':1}], 'libraryArtists':['A','B']}
    raw={**PROFILE,'artists':[{'artist':'A','weight':-.9},{'artist':'B','weight':.9}]}
    assert grounded_profile(raw,evidence)['artists']==[]
