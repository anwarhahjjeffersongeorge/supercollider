
Pattern : AbstractFunction {


	// concatenate Patterns
	++ { arg aPattern;
		^Pseq.new([this, aPattern])
	}
	// compose Patterns
	<> { arg aPattern;
		^Pchain(this, aPattern)
	}

	play { arg clock, protoEvent, quant;
		^this.asEventStreamPlayer(protoEvent).play(clock, false, quant)
	}
	// phase causes pattern to start somewhere in the current measure rather than on a downbeat
	// offset allows pattern to compute ahead a bit to allow negative lags for strummed chords
	// and to ensure one pattern computes ahead of another

	asStream { ^Routine({ arg inval; this.embedInStream(inval) }) }
	iter { ^this.asStream }

	asEventStreamPlayer { arg protoEvent;
		^EventStreamPlayer(this.asStream, protoEvent);
	}
	embedInStream { arg inval;
		^this.asStream.embedInStream(inval);
	}
	do { arg function;
		this.asStream.do(function)
	}

	// filtering operations
	collect { arg function;
		^Pcollect.new(function, this)
	}
	select { arg function;
		^Pselect.new(function, this)
	}
	reject { arg function;
		^Preject.new(function, this)
	}

	// function composition
	composeUnaryOp { arg operator;
		^Punop.new(operator, this)
	}
	composeBinaryOp { arg operator, pattern, adverb;
		^Pbinop.new(operator, this, pattern, adverb)
	}
	reverseComposeBinaryOp { arg operator, pattern, adverb;
		^Pbinop.new(operator, pattern, this, adverb)
	}
	composeNAryOp { arg selector, argList;
		^Pnaryop.new(selector, this, argList);
	}

	//////////////////////

	mtranspose { arg n; ^Paddp(\mtranspose, n, this) }
	ctranspose { arg n; ^Paddp(\ctranspose, n, this) }
	gtranspose { arg n; ^Paddp(\gtranspose, n, this) }
	detune { arg n; ^Paddp(\detune, n, this) }

	scaleDur { arg x; ^Pmulp(\dur, x, this) }
	addDur { arg x; ^Paddp(\dur, x, this) }
	stretch { arg x; ^Pmulp(\stretch, x, this) }
	lag { arg t; ^Plag(t, this) }

	legato { arg x; ^Pmulp(\legato, x, this) }

	db { arg db; ^Paddp(\db, db, this) }

	clump { arg n; ^Pclump(n, this) }
	flatten { arg n = 1; ^Pflatten(n, this) }
	repeat { arg n=inf; ^Pn(this, n) }
	keep { arg n; ^Pfin(n, this) }
	drop { arg n; ^Pdrop(n, this) }
	stutter { arg n; ^Pstutter(n, this) }
	finDur { arg dur, tolerance = 0.001; ^Pfindur(dur, this, tolerance) }
	fin { arg n; ^Pfin(n, this) }

	trace { arg key, printStream, prefix=""; ^Ptrace(this, key, printStream, prefix) }
	differentiate { ^Pdiff(this) }
}

Pfunc : Pattern {
	var <>nextFunc; // Func is evaluated for each next state
	var <>resetFunc; // Func is evaluated for each next state
	*new { arg nextFunc, resetFunc;
		^super.newCopyArgs(nextFunc, resetFunc)
	}
	storeArgs { ^[nextFunc] ++ resetFunc }
	asStream {
//		^FuncStreamAsRoutine.new(nextFunc, resetFunc)
	^FuncStream.new(nextFunc, resetFunc)
	}
//	embedInStream { arg inval; loop { inval = yield(nextFunc.value(inval)) }  }
}

Prout : Pattern {
	var <>routineFunc;
	*new { arg routineFunc;
		^super.newCopyArgs(routineFunc)
	}
	storeArgs { ^[routineFunc] }
	asStream {
		^Routine.new(routineFunc)
	}
}

Proutine : Prout {
	embedInStream { arg inval; ^routineFunc.value(inval) }
}

Pfuncn : Pattern {
	var <>func, <>repeats;
	*new { arg func, repeats = 1;
		^super.newCopyArgs(func, repeats)
	}
	storeArgs { ^[func,repeats] }
	embedInStream {  arg inval;
		repeats.value.do({
			inval = func.value(inval).yield;
		});
		^inval
	}
}


// Punop and Pbinop are used to create patterns in response to math operations
Punop : Pattern {
	var <>operator, <>a;
	*new { arg operator, a;
		^super.newCopyArgs(operator, a)
	}

	storeOn { arg stream; stream <<< a << "." << operator }

	embedInStream { arg inval;
		var stream, outval;
		stream = a.asStream;
		loop {
			outval = stream.next(inval);
			if (outval.isNil) { ^inval };
			inval = yield(outval.perform(operator));
		}
	}

	asStream {
		var stream = a.asStream;
		^UnaryOpStream.new(operator, stream);
	}
}

Pbinop : Pattern {
	var <>operator, <>a, <>b, <>adverb;
	*new { arg operator, a, b, adverb;
		^super.newCopyArgs(operator, a, b, adverb)
	}

	storeOn { arg stream;
			stream << "(" <<< a << " " << operator.asBinOpString;
			if(adverb.notNil) { stream << "." << adverb };
			stream << " " <<< b << ")"
	}

	asStream {
		var streamA = a.asStream;
		var streamB = b.asStream;
		if (adverb.isNil) {
			^BinaryOpStream.new(operator, streamA, streamB);
		};
		if (adverb == 'x') {
			^BinaryOpXStream.new(operator, streamA, streamB);
		};
		^nil
	}
}

Pnaryop : Pattern {
	var <>operator, <>a, <>arglist;
	*new { arg operator, a, arglist;
		^super.newCopyArgs(operator, a, arglist)
	}
	storeOn { arg stream; stream <<< a << "." << operator << "(" <<<* arglist << ")" }

	embedInStream { arg inval;
		var streamA, streamlist, vala, values, isNumeric;
		streamA = a.asStream;
		isNumeric = arglist.every { arg item;
			item.isNumber or: {item.class === Symbol} }; // optimization

		if (isNumeric) {
			loop {
				vala = streamA.next(inval);
				if (vala.isNil) { ^inval };
				inval = yield(vala.performList(operator, arglist));
			}
		}{
			streamlist = arglist.collect({ arg item; item.asStream });
			loop {
				vala = streamA.next(inval);
				if (vala.isNil) { ^inval };
				values = streamlist.collect({ arg item;
					var result = item.next(inval);
					if (result.isNil) { ^inval };
					result
				});
				inval = yield(vala.performList(operator, values));
			}
		};
	}

	asStream {
		var streamA = a.asStream;
		var streamlist = arglist.collect({ arg item; item.asStream });
		^NAryOpStream.new(operator, streamA, streamlist);
	}
}

PdegreeToKey : Pnaryop {
	*new { arg pattern, scale, stepsPerOctave=12;
		^super.new('degreeToKey', pattern, [scale, stepsPerOctave])
	}
	// this is not reversible
	// but it would save as something that played the same
	//storeArgs { ^[ pattern, scale, stepsPerOctave ] }
}

Pchain : Pattern {
	var <>patterns;
	*new { arg ... patterns;
		^super.newCopyArgs(patterns);
	}
	<> { arg aPattern;
		var list;
		list = patterns.copy.add(aPattern);
		^this.class.new(*list)
	}
	embedInStream { arg inval;
		var streams, inevent;
		streams = patterns.collect(_.asStream);
		loop {
			streams.reverseDo { |str|
				inevent = str.next(inval);
				if(inevent.isNil) { ^inval };
				inval = inevent;
			};
			inval = yield(inevent);
			if (inval.isNil) {
				streams.reverseDo { |str|
					str.next(inval);
				};
				^nil.yield;
			};
		};
	}
	storeOn { arg stream;
			stream << "(";
			patterns.do { |item,i|  if(i != 0) { stream << " <> " }; stream <<< item; };
			stream << ")"
	}
}


Pevent : Pattern {
	var <>pattern, <>event;

	*new { arg pattern, event;
		^super.newCopyArgs(pattern, event);
	}
	storeArgs { ^[pattern, event] }
	embedInStream { arg inval;
		var outval;
		var stream = pattern.asStream;
		loop {
			outval = stream.next(event);
			if (outval.isNil) {^inval};
			inval = outval.yield
		 }
	}
}


Pbind : Pattern {
	var <>patternpairs;
	*new { arg ... pairs;
		if (pairs.size.odd, { Error("Pbind should have even number of args.\n").throw; });
		^super.newCopyArgs(pairs)
	}

	storeArgs { ^patternpairs }
	embedInStream { arg inevent;
		var event;
		var sawNil = false;
		var streampairs = patternpairs.copy;
		var endval = streampairs.size - 1;

		forBy (1, endval, 2) { arg i;
			streampairs.put(i, streampairs[i].asStream);
		};

		loop {
			if (inevent.isNil) { ^nil.yield };
			event = inevent.copy;
			forBy (0, endval, 2) { arg i;
				var name = streampairs[i];
				var stream = streampairs[i+1];
				var streamout = stream.next(event);
				if (streamout.isNil) { ^inevent };

				if (name.isSequenceableCollection) {
					if (name.size > streamout.size) {
						("the pattern is not providing enough values to assign to the key set:" + name).warn;
						^inevent
					};
					name.do { arg key, i;
						event.put(key, streamout[i]);
					};
				}{
					event.put(name, streamout);
				};

			};
			inevent = event.yield;
		}
	}
}

Pmono : Pattern {
	var <>synthName, <>patternpairs;
	*new { arg name ... pairs;
		if (pairs.size.odd, { Error("Pmono should have odd number of args.\n").throw; });
		^super.newCopyArgs(name.asSymbol, pairs)
	}

	embedInStream { | inevent |
		^PmonoStream(this).embedInStream(inevent)
	}
}

PmonoArtic : Pmono {
	embedInStream { |inevent|
		^PmonoArticStream(this).embedInStream(inevent)
	}
}


Pseries : Pattern {	// arithmetic series
	var <>start=0, <>step=1, <>length=inf;
	*new { arg start = 0, step = 1, length=inf;
		^super.newCopyArgs(start, step, length)
	}
	storeArgs { ^[start,step,length] }

	embedInStream { arg inval;
		var outval, counter = 0;
		var cur = start.value;
		var len = length.value;
		var stepStr = step.asStream, stepVal;
		while { counter < len } {
			stepVal = stepStr.next(inval);
			if(stepVal.isNil) { ^inval };
			outval = cur;
			cur = cur + stepVal;
			counter = counter + 1;
			inval = outval.yield;
		};
		^inval;
	}
}

Pgeom : Pattern {	// geometric series
	var <>start=1.0, <>grow=1.0, <>length=inf;
	*new { arg start = 0, grow = 1, length=inf;
		^super.newCopyArgs(start, grow, length)
	}
	storeArgs { ^[start,grow,length] }
	embedInStream { arg inval;
		var outval, counter = 0;
		var cur = start.value;
		var len = length.value;
		var growStr = grow.asStream, growVal;

		while { counter < len } {
			growVal = growStr.next(inval);
			if(growVal.isNil) { ^inval };
			outval = cur;
			cur = cur * growVal;
			counter = counter + 1;
			inval = outval.yield;
		};
		^inval;
	}
}


Pbrown : Pattern {
	var <>lo, <>hi, <>step, <>length;

	*new { arg lo=0.0, hi=1.0, step=0.125, length=inf;
		^super.newCopyArgs(lo, hi, step, length)
	}

	storeArgs { ^[lo,hi,step,length] }

	embedInStream { arg inval;
		var cur;
		var loStr = lo.asStream, loVal;
		var hiStr = hi.asStream, hiVal;
		var stepStr = step.asStream, stepVal;

		loVal = loStr.next(inval);
		hiVal = hiStr.next(inval);
		stepVal = stepStr.next(inval);
		cur = rrand(loVal, hiVal);
		if(loVal.isNil or: { hiVal.isNil } or: { stepVal.isNil }) { ^inval };

		length.value.do {
			loVal = loStr.next(inval);
			hiVal = hiStr.next(inval);
			stepVal = stepStr.next(inval);
			if(loVal.isNil or: { hiVal.isNil } or: { stepVal.isNil }) { ^inval };
			cur = this.calcNext(cur, stepVal).fold(loVal, hiVal);
			inval = cur.yield;
		};

		^inval;
	}

	calcNext { arg cur, step;
		^cur + step.xrand2
	}
}

Pgbrown : Pbrown {
	calcNext { arg cur, step;
		^cur * (1 + step.xrand2)
	}
}

Pwhite : Pattern {
	var <>lo, <>hi, <>length;
	*new { arg lo=0.0, hi=1.0, length=inf;
		^super.newCopyArgs(lo, hi, length)
	}
	storeArgs { ^[lo,hi,length] }
	embedInStream { arg inval;
		var loStr = lo.asStream;
		var hiStr = hi.asStream;
		var hiVal, loVal;
		length.value.do({
			hiVal = hiStr.next(inval);
			loVal = loStr.next(inval);
			if(hiVal.isNil or: { loVal.isNil }) { ^inval };
			inval = rrand(loVal, hiVal).yield;
		});
		^inval;
	}
}

Pprob : Pattern {
	var <>hi, <>lo, <>length, <tableSize, <distribution, <table;

	*new { arg distribution, lo=0.0, hi=1.0, length=inf, tableSize;
		^super.newCopyArgs(hi, lo, length, tableSize).distribution_(distribution);
	}
	distribution_ { arg list;
		var n = tableSize ?? { max(64, list.size) }; // resample, if too small
		distribution = list;
		table = distribution.asRandomTable(n);
	}
	tableSize_ { arg n;
		tableSize = n;
		this.distribution_(distribution);
	}
	embedInStream { arg inval;
		var loStr = lo.asStream;
		var hiStr = hi.asStream;
		var hiVal, loVal;

		length.value.do {
			loVal = loStr.next(inval);
			hiVal = hiStr.next(inval);
			if(hiVal.isNil or: { loVal.isNil }) { ^inval };
			inval = ((table.tableRand * (hiVal - loVal)) + loVal).yield;		};

		^inval;
	}
}

Pstep2add : Pattern {
	var <>pattern1, <>pattern2;
	*new { arg pattern1, pattern2;
		^super.newCopyArgs(pattern1, pattern2)
	}
	storeArgs { ^[pattern1,pattern2] }
	embedInStream { arg inval;
		var stream1, stream2, val1, val2;

		stream1 = pattern1.asStream;
		while {
			(val1 = stream1.next(inval)).notNil;
		}{
			stream2 = pattern2.asStream;
			while {
				(val2 = stream2.next(inval)).notNil;
			}{
				inval = yield( val1 + val2 );
			};
		};
		^inval
	}
}

Pstep3add : Pattern {
	var <>pattern1, <>pattern2, <>pattern3;
	*new { arg pattern1, pattern2, pattern3;
		^super.newCopyArgs(pattern1, pattern2, pattern3)
	}
	storeArgs { ^[pattern1,pattern2,pattern3] }

	embedInStream { arg inval;
		var stream1, stream2, stream3, val1, val2, val3;

		stream1 = pattern1.asStream;
		while {
			(val1 = stream1.next(inval)).notNil;
		}{
			stream2 = pattern2.asStream;
			while {
				(val2 = stream2.next(inval)).notNil;
			}{
				stream3 = pattern3.asStream;
				while {
					(val3 = stream3.next(inval)).notNil;
				}{
					inval = yield( val1 + val2 + val3 );
				};
			};
		};
		^inval;
	}
}


PstepNfunc : Pattern {
	var <function, <>patterns;
	*new { arg function, patterns;
		^super.newCopyArgs(function ? { |x| x }, patterns)
	}
	storeArgs { ^[function,patterns] }
	embedInStream { arg inval;
		var val;
		var size = patterns.size;
		var max = size - 1;
		var streams = Array.newClear(size);
		var vals = Array.newClear(size);
		var f = { arg inval, level=0;
				var val;
				streams[level] = patterns[level].asStream;
				while{
					vals[level] = val = streams[level].next(inval);
					val.notNil;
				}{
					if(level < max) {
						inval = f.value(inval, level + 1)
					}{
						inval = yield(function.value(vals));
					}
				};
			inval;
		};
		^f.value(inval);
	}

}

PstepNadd : PstepNfunc {
	*new { arg ... patterns;
		^super.new({ arg vals; vals.sum }, patterns)
	}
	storeArgs { ^patterns }
}

// returns relative time (in beats) from moment of embedding

Ptime : Pattern {
	var <>repeats;
	*new { arg repeats=inf;
		^super.newCopyArgs(repeats)
	}
	storeArgs { ^[repeats] }
	embedInStream { arg inval;
		var start = thisThread.beats;
		repeats.value.do { inval = (thisThread.beats - start).yield };
		^inval
	}
}

// if an error is thrown in the stream, func is evaluated

Pprotect : FilterPattern {
	var <>func;
	*new { arg pattern, func;
		^super.new(pattern).func_(func)
	}
	storeArgs { ^[ pattern, func ] }
	asStream {
		var rout = Routine(pattern.embedInStream(_));
		rout.exceptionHandler = { |error|
			func.value(error, rout);
			nil.handleError(error)
		};
		^rout
	}
}



// access a key from the input event
Pkey : Pattern {
	var	<>key, <>repeats;
	*new { |key|
		^super.newCopyArgs(key)
	}
	storeArgs { ^[key] }
		// avoid creating a routine
	asStream {
		var	keystream = key.asStream;
		^FuncStream({ |inevent|
			inevent !? { inevent[keystream.next(inevent)] }
		});
	}
}

Pif : Pattern {
	var	<>condition, <>iftrue, <>iffalse, <>default;
	*new { |condition, iftrue, iffalse, default|
		^super.newCopyArgs(condition, iftrue, iffalse, default)
	}
	storeArgs { ^[condition, iftrue, iffalse,default] }
	asStream {
		var	condStream = condition.asStream,
			trueStream = iftrue.asStream,
			falseStream = iffalse.asStream;

		^FuncStream({ |inval|
			var test;
			(test = condStream.next(inval)).isNil.if({
				nil
			}, {
				test.if({
					trueStream.next(inval) ? default
				}, {
					falseStream.next(inval) ? default
				});
			});
		}, {		// reset func
			condStream.reset;
			trueStream.reset;
			falseStream.reset;
		})
	}
}
