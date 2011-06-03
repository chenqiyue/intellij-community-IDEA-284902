/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.formatter;

import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Denis Zhdanov
 * @since Sep 21, 2010 2:49:43 PM
 */
public class StaticTextWhiteSpaceDefinitionStrategy extends AbstractWhiteSpaceFormattingStrategy {

  private final Set<CharSequence> myWhiteSpaces = new HashSet<CharSequence>();

  public StaticTextWhiteSpaceDefinitionStrategy(@NotNull CharSequence ... whiteSpaces) {
    myWhiteSpaces.addAll(Arrays.asList(whiteSpaces));
  }

  @Override
  public int check(@NotNull CharSequence text, int start, int end) {
    for (CharSequence whiteSpace : myWhiteSpaces) {
      if (CharArrayUtil.indexOf(text, whiteSpace, start, end) == start) {
        return start + whiteSpace.length();
      }
    }

    return start;
  }
}
