/*
 * Copyright (C) 2019 eric
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.units.erallab.hmsrobots.objects.immutable;

import java.io.Serializable;
import org.dyn4j.geometry.Vector2;

/**
 *
 * @author eric
 */
public class Point2 implements Serializable {
  
  public final double x;
  public final double y;

  public Point2(double x, double y) {
    this.x = x;
    this.y = y;
  }

  public Point2(Vector2 v) {
    x = v.x;
    y = v.y;
  }
  
  
  
}