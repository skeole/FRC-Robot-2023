package frc.robot.subsystems;

import static frc.robot.Constants.*;

import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.NeutralMode;
import com.ctre.phoenix.motorcontrol.can.TalonFX;

import edu.wpi.first.wpilibj.ADIS16448_IMU;

public class SwerveDriveTrain {

    private SwerveModule right_front;
    private SwerveModule left_front;
    private SwerveModule right_back;
    private SwerveModule left_back;
    private ADIS16448_IMU gyro;

    private final double starting_yaw;

    public SwerveDriveTrain() {
        right_front = new SwerveModule(rf_direction, rf_driving, new double[] {1, 1}, 0.5);
        left_front = new SwerveModule(lf_direction, lf_driving, new double[] {-1, 1}, 0.5);
        right_back = new SwerveModule(rb_direction, rb_driving, new double[] {1, -1}, 0.5);
        left_back = new SwerveModule(lb_direction, lb_driving, new double[] {-1, -1}, 0.5);
        gyro = new ADIS16448_IMU(); //damn it's crazy that this is the 16,448th edition
        gyro.calibrate();
        gyro.reset();
        starting_yaw = gyro.getAngle() * Math.PI / 180.0; //it goes in degrees which is kinda stupid but who cares
                            //we can also do .getAngle(), .getAngleX(), .getAngleY(), or .getAngleZ()
    }


    public void drive(double left_stick_x, double left_stick_y, double right_stick_x, double right_stick_y, double speed_factor, boolean makeX) {
        
        if (makeX) {
            brake();
            return;
        }

        double mgn = Math.sqrt(left_stick_x * left_stick_x + left_stick_y * left_stick_y);
        double mgn2 = Math.sqrt(right_stick_x * right_stick_x + right_stick_y * right_stick_y);

        if (mgn < 0.2 && mgn2 < 0.2) {
            right_front.brake();
            left_front.brake();
            right_back.brake();
            left_back.brake();
            return;
        }
        
        double left_stick_angle = vectorToAngle(new double[] {left_stick_x, left_stick_y});
        double strafe_angle = normalizeAngle(left_stick_angle - angle());
        double[] strafevector = angleToVector(strafe_angle);
        strafevector[0] *= mgn;
        strafevector[1] *= mgn;
        double turning_factor;

        if (absolute_directing) {
            double right_stick_angle = vectorToAngle(new double[] {right_stick_x, right_stick_y});

            double error = normalizeAngle(right_stick_angle - angle()); 
            turning_factor = Math.max(Math.min(error * 4.0, 1), -1) * (mgn2 > 0.2 ? mgn2 : 0);
                //if the right button isn't pressed we don't want to turn
        } else {
            turning_factor = right_stick_x;
        }

        double multiplier = Math.min(speed_factor, 1) / Math.max(Math.max(Math.max(right_front.calculateTotalPower(strafevector, turning_factor), left_front.calculateTotalPower(strafevector, turning_factor)), Math.max(right_back.calculateTotalPower(strafevector, turning_factor), left_back.calculateTotalPower(strafevector, turning_factor))), 1.0);
        right_front.drive(strafevector, turning_factor, multiplier);
        left_front.drive(strafevector, turning_factor, multiplier);
        right_back.drive(strafevector, turning_factor, multiplier);
        left_back.drive(strafevector, turning_factor, multiplier);
    }

    public double angle() {
        return normalizeAngle(starting_yaw - gyro.getAngle() * Math.PI / 180.0);
    }

    private void brake() {
        right_front.makeX();
        left_front.makeX();
        right_back.makeX();
        left_back.makeX();
    }
}

class SwerveModule { //each module requires 2 Talon FX motors

    private TalonFX direction_motor;
    private TalonFX driving_motor;

    private final double x_;
    private final double y_;
    private final double tolerance;

    public SwerveModule(int direction_motor_port, int driving_motor_port, double[] position, double tolerance_) {
                                    //position: relative position from the center of the circumcircle
                                    //units don't matter, they just have to be consistent
        direction_motor = new TalonFX(direction_motor_port);
        direction_motor.setSelectedSensorPosition(0, 0, 0);

        driving_motor = new TalonFX(driving_motor_port);

        direction_motor.setNeutralMode(NeutralMode.Brake);
        driving_motor.setNeutralMode(NeutralMode.Brake);

        direction_motor.setInverted(false);
        driving_motor.setInverted(false);

        double radius = Math.sqrt(position[0] * position[0] + position[1] * position[1]);

        x_ = position[1] / radius; //ex, (1, 2) --> (2, -1); clockwise rotation
        y_ = 0 - position[0] / radius;

        tolerance = tolerance_ * Math.PI / 180.0;
    }

    public double calculateTotalPower(double[] strafevector, double turnfactor) {
        double x = strafevector[0] + turnfactor * x_; //positive turnfactor --> rotate clockwise
        double y = strafevector[1] + turnfactor * y_;
        return Math.sqrt(x * x + y * y);
    }

    public void drive(double[] strafevector, double turnfactor, double multiplier) {
        double[] vector = {strafevector[0] + turnfactor * x_, strafevector[1] + turnfactor * y_};
        vector[0] *= multiplier;
        vector[1] *= multiplier;
        setModule(vector);
    }

    public void brake() {
        direction_motor.set(ControlMode.PercentOutput, 0);
        driving_motor.set(ControlMode.PercentOutput, 0);
    }

    public void makeX() {
        double[] targetvector = {-y_, x_};
        double targetangle = vectorToAngle(targetvector);
        double error = normalizeAngle(targetangle - getAngle());

        if (Math.abs(error) > Math.PI / 2.0) error += Math.PI * (error > 0 ? -1 : 1);

        direction_motor.set(ControlMode.PercentOutput, Math.abs(error) < 0.02 ? 0 : Math.max(Math.min(error * 4.0, 1), -1));
        driving_motor.set(ControlMode.PercentOutput, 0);
    }

    private double getAngle() {
        return normalizeAngle(direction_motor.getSelectedSensorPosition(0) / ticks_per_radian);
    }

    private void setModule(double[] vector) {
        double magnitude_ = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1]);

        if (magnitude_ == 0) {
            brake();
            return;
        }

        setModule(vectorToAngle(vector), magnitude_);
    }

    private void setModule(double angle, double magnitude) {
        double error = normalizeAngle(angle - getAngle()); //power the wheel forward if error > 0

        int negate = 1;

        if (Math.abs(error) > Math.PI / 2.0) { //if we're more than 90 degrees away, invert angle and power
            negate = -1;
            error += Math.PI * (error > 0 ? -1 : 1);
        }

        direction_motor.set(ControlMode.PercentOutput, Math.abs(error) < 0.02 ? 0 : Math.max(Math.min(error * 4.0, 1), -1));
                        //max power at 0.25 radians
        driving_motor.set(ControlMode.PercentOutput, Math.abs(error) < tolerance ? 0 : magnitude * negate);
    }
}