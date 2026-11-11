"""Synthetic observations shared by preparation and training regression tests."""

def player(weapon,hp,armor=0):
    return dict(x=0.,y=63.,z=-100.,vx=0.,vy=0.,vz=0.,yaw=0.,pitch=0.,health_observed=hp,
                absorption_observed=0.,armor=armor,hurt=0,weapon=weapon)

def frame(tick, swinging=True, weapon='sword', moving=False):
    own = player('fist', 20)
    enemy = player(weapon, 20, 5)
    enemy.update(z=-98., swinging=swinging, swing_progress=tick % 6, head_yaw=180., yaw=180.)
    if moving:
        enemy['x'] = tick*.03
    return dict(type='frame', episode=1, life=1, tick=tick, self=own, opponent=enemy,
                opponent_aims_at_self=True, nearby_other_players=0,
                policy_features=[tick % 2]+[0.]*15, policy_eligible=True,
                action=dict(forward=tick % 2, side=0, jump=False, sneak=False, attack_requested=True))