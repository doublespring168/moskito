package net.java.dev.moskito.core.treshold.guard;

import net.java.dev.moskito.core.treshold.ThresholdStatus;

/**
 * A barrier path guard that works with double values.
 * @author lrosenberg
 */
public class DoubleBarrierPassGuard extends BarrierPassGuard{
	/**
	 * Limit value.
	 */
	private double barrierValue;

	public DoubleBarrierPassGuard(ThresholdStatus aTargetStatus, double targetValue, GuardedDirection aDirection){
		super(aTargetStatus, aDirection);
		barrierValue = targetValue;
	}
	
	@Override
	public String getValueAsString(){
		return ""+barrierValue;
	}
	@Override
	protected Number getValueAsNumber(String aValue){
		return Double.parseDouble(aValue);
	}
	@Override
	protected Number getBarrierValueAsNumber(){
		return barrierValue;
	}
	
}
