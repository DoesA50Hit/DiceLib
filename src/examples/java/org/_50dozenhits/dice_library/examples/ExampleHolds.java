package org._50dozenhits.dice_library.examples;

import org._50dozenhits.dice_library.prop.Tether;
import org._50dozenhits.dice_library.state.CasterState;

// From the DiceLib Guide, Timing and State. docs/build_guide.py copies this class back into that page.
public final class ExampleHolds {
    // One chain per caster, for an ability that holds someone. CasterState forgets the chain when the caster dies or
    // leaves and runs the lambda on it, which pulls the chain back in so none is left hanging in the world.
    public static final CasterState<Tether> HOLDS = CasterState.create((caster, chain) -> chain.retract());
}
